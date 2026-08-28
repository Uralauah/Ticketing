package ticketing.domain.reservation.dto;

import ticketing.domain.reservation.entity.Reservation;
import ticketing.domain.reservation.entity.ReservationStatus;

import java.time.LocalDateTime;

public record ReservationRes(
        Long reservationId,
        Long seatId,
        int price,
        ReservationStatus status,
        LocalDateTime reservedAt
) {
    public static ReservationRes from(Reservation reservation) {
        return new ReservationRes(
                reservation.getId(),
                reservation.getSeat().getId(),
                reservation.getPrice(),
                reservation.getStatus(),
                reservation.getReservationTime()
        );
    }
}
