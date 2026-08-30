package ticketing.domain.reservation.event;

/** 예약 트랜잭션 안에서 발행, 커밋 후에만 처리 */
public record SeatReservedEvent(long scheduleId, long seatId) {
}
