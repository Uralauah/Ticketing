package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 일정별 좌석 상태 DB → Redis 적재 (락을 잡은 요청 하나만 DB 조회)
 * HSETNX 적재라 먼저 기록된 변경을 덮어쓰지 않음
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatStatusInitializer {
    private static final Duration INIT_LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration INIT_WAIT_TIMEOUT = Duration.ofSeconds(2);
    private static final long INIT_POLL_INTERVAL_MS = 50;

    private final SeatRepository seatRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatStatusRedisRepository redisRepo;

    public void ensureInitialized(long scheduleId) {
        if (redisRepo.isInitDone(scheduleId)) {
            return;
        }

        String owner = UUID.randomUUID().toString();
        if (redisRepo.tryInitLock(scheduleId, owner, INIT_LOCK_TTL)) {
            try {
                // 락 획득 전에 다른 인스턴스가 끝냈을 수 있으니 재확인
                if (!redisRepo.isInitDone(scheduleId)) {
                    initialize(scheduleId);
                }
            } finally {
                redisRepo.releaseInitLock(scheduleId, owner);
            }
            return;
        }

        if (!awaitInitDone(scheduleId)) {
            throw new CustomException(SeatExceptionCode.SEAT_STATUS_NOT_READY);
        }
    }

    private void initialize(long scheduleId) {
        if (!concertScheduleRepository.existsById(scheduleId)) {
            throw new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND);
        }

        long startedAt = System.nanoTime();
        List<SeatStatusRes> seats = seatRepository.findSeatStatusByScheduleId(scheduleId);
        Map<String, String> statuses = new HashMap<>(seats.size() * 2);
        for (SeatStatusRes seat : seats) {
            statuses.put(String.valueOf(seat.seatId()), seat.status().name());
        }

        redisRepo.initStatusesIfAbsent(scheduleId, statuses);
        redisRepo.initVersionIfAbsent(scheduleId);
        redisRepo.markInitDone(scheduleId);
        log.info("좌석 상태 초기화 완료: scheduleId={}, seats={}, {}ms",
                scheduleId, seats.size(), (System.nanoTime() - startedAt) / 1_000_000);
    }

    private boolean awaitInitDone(long scheduleId) {
        long deadline = System.nanoTime() + INIT_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(INIT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (redisRepo.isInitDone(scheduleId)) {
                return true;
            }
        }
        return false;
    }
}
