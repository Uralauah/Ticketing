package ticketing.domain.concert.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.reservation.event.SeatReservedEvent;

/**
 * 예약 커밋 후 좌석 상태 변경을 Redis에 기록·발행 (롤백된 예약은 전파 안 함)
 *
 * 실패는 예외 대신 로그와 지표로 남김 (afterCommit 예외는 커밋한 쪽으로 전파됨)
 */
@Slf4j
@Component
public class SeatReservedEventListener {

    private final SeatStatusLiveService liveService;
    private final Counter recordFailures;

    public SeatReservedEventListener(SeatStatusLiveService liveService, MeterRegistry meterRegistry) {
        this.liveService = liveService;
        this.recordFailures = Counter.builder("ticketing.seat.status.record.failures")
                .description("예약 커밋 후 좌석 상태 기록(Redis)에 실패한 횟수")
                .register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeatReserved(SeatReservedEvent event) {
        try {
            liveService.recordChange(event.scheduleId(), event.seatId(), SeatStatus.RESERVED);
        } catch (RuntimeException e) {
            recordFailures.increment();
            log.error("좌석 상태 기록 실패(예약은 커밋됨): scheduleId={}, seatId={}", event.scheduleId(), event.seatId(), e);
        }
    }
}
