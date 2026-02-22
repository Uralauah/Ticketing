package ticketing.domain.concert.dto;

public record SeatMetaRes(
        Long seatId,
        String seatNum,
        int price
) {}