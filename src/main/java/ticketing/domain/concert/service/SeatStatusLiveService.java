package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketing.domain.concert.dto.*;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;
import ticketing.infra.redis.seatstatus.SeatStatusChangeCodec;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeatStatusLiveService {

    private final SeatRepository seatRepository;
    private final ConcertScheduleRepository concertScheduleRepository;

    private final SeatStatusRedisRepository redisRepo;
    private final SeatStatusChangeCodec codec;

    private static final long CHANGE_LOG_WINDOW = 100_000L;
    private static final Duration INIT_LOCK_TTL = Duration.ofSeconds(10);

    @Transactional(readOnly = true)
    public void ensureInitialized(long scheduleId) {
        if (redisRepo.isInitDone(scheduleId)) return;

        boolean locked = redisRepo.tryInitLock(scheduleId, INIT_LOCK_TTL);
        if (!locked) {
            // 다른 스레드가 초기화 중일 수 있으니 잠깐 대기 후 재확인
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                if (redisRepo.isInitDone(scheduleId)) return;
            }
        }

        boolean exists = concertScheduleRepository.existsById(scheduleId);
        if (!exists) throw new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND);

        List<SeatStatusRes> db = seatRepository.findSeatStatusByScheduleId(scheduleId);

        Map<String, String> map = new HashMap<>(db.size());
        for (SeatStatusRes s : db) {
            map.put(String.valueOf(s.seatId()), s.status().name());
        }

        redisRepo.putAllStatuses(scheduleId, map);
        redisRepo.initVersionIfAbsent(scheduleId);
        redisRepo.markInitDone(scheduleId);
    }

    public SeatStatusSnapshotRes snapshot(long scheduleId) {
        ensureInitialized(scheduleId);

        long current = redisRepo.currentVersion(scheduleId);
        Map<Object, Object> entries = redisRepo.getAllStatuses(scheduleId);

        List<SeatStatusRes> seats = entries.entrySet().stream()
                .map(e -> new SeatStatusRes(
                        Long.parseLong(String.valueOf(e.getKey())),
                        SeatStatus.valueOf(String.valueOf(e.getValue()))
                ))
                .sorted(Comparator.comparingLong(SeatStatusRes::seatId))
                .toList();

        return new SeatStatusSnapshotRes(current, seats);
    }

    /** SSE 재연결 시 replay용(또는 폴백용) */
    public SeatStatusDeltaRes delta(long scheduleId, long since) {
        ensureInitialized(scheduleId);

        long current = redisRepo.currentVersion(scheduleId);

        long minSinceOk = Math.max(0, current - CHANGE_LOG_WINDOW);
        if (since < minSinceOk) {
            return new SeatStatusDeltaRes(current, true, List.of());
        }

        if (since >= current) {
            return new SeatStatusDeltaRes(current, false, List.of());
        }

        Set<String> raw = redisRepo.rangeChanges(scheduleId, since + 1, current);
        if (raw == null || raw.isEmpty()) {
            return new SeatStatusDeltaRes(current, false, List.of());
        }

        List<SeatStatusChangeRes> changes = raw.stream()
                .map(codec::decode)
                .collect(Collectors.toList());

        return new SeatStatusDeltaRes(current, false, changes);
    }

    /** DB 커밋 이후 호출되어야 함 */
    public long recordChange(long scheduleId, long seatId, SeatStatus status) {
        ensureInitialized(scheduleId);
        return redisRepo.recordChangeAndPublishAtomic(scheduleId, seatId, status, CHANGE_LOG_WINDOW);
    }
}