package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ticketing.domain.concert.dto.*;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.infra.redis.seatstatus.SeatStatusChangeCodec;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeatStatusLiveService {

    private final SeatStatusInitializer initializer;
    private final SeatStatusRedisRepository redisRepo;
    private final SeatStatusChangeCodec codec;

    private static final long CHANGE_LOG_WINDOW = 100_000L;

    public void ensureInitialized(long scheduleId) {
        initializer.ensureInitialized(scheduleId);
    }

    public SeatStatusSnapshotRes snapshot(long scheduleId) {
        ensureInitialized(scheduleId);

        // version을 먼저 읽음 → 사이에 끼어든 변경은 replay 때 한 번 더 적용될 뿐 (멱등)
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

    /**
     * DB 커밋 이후 호출되어야 함
     * 초기화는 HSETNX 적재라 먼저 기록한 상태를 덮어쓰지 않으므로 초기화 대기 없음
     */
    public long recordChange(long scheduleId, long seatId, SeatStatus status) {
        return redisRepo.recordChangeAndPublishAtomic(scheduleId, seatId, status, CHANGE_LOG_WINDOW);
    }
}