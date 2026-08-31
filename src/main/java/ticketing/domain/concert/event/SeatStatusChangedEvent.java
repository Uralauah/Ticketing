package ticketing.domain.concert.event;

import ticketing.domain.concert.entity.SeatStatus;

/** 좌석 상태 변경 트랜잭션 안에서 발행, 커밋 후에만 처리 */
public record SeatStatusChangedEvent(
        long outboxId, // 같은 변경의 Outbox 행 id
        long scheduleId,
        long seatId,
        SeatStatus status
) {}
