package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ticketing.global.exception.CustomException;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;

/** 일정별 남은 좌석 수 (대기열 매진 판단용) */
@Component
@RequiredArgsConstructor
public class SeatInventory {
    private final SeatStatusRedisRepository redisRepo;
    private final SeatStatusInitializer initializer;

    /** 남은 좌석이 0으로 확인된 경우에만 true */
    public boolean isSoldOut(long scheduleId) {
        Long remaining = redisRepo.remainingSeats(scheduleId);
        if (remaining == null) {
            // 카운터는 좌석 상태 적재 후 생성, 적재 중(503)이면 매진 아님으로 처리
            try {
                initializer.ensureInitialized(scheduleId);
            } catch (CustomException e) {
                return false;
            }
            remaining = redisRepo.remainingSeats(scheduleId);
        }
        return remaining != null && remaining <= 0;
    }
}
