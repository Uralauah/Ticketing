package ticketing.domain.reservation.dto;

public record ReservationReq(
        Long userId,
        Long scheduleId,
        Long seatId
) {
}
