package ticketing.domain.concert.dto;

import ticketing.domain.concert.entity.SeatStatus;

public record SeatStatusRes(
        Long seatId,
        SeatStatus status
) {}