package ticketing.domain.concert.dto;

import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;

public record SeatRes(
        String seatNum,
        int price,
        SeatStatus status
) {
    public static SeatRes fromEntity(Seat seat){
        return new SeatRes(seat.getSeatNum(), seat.getPrice(), seat.getStatus());
    }
}
