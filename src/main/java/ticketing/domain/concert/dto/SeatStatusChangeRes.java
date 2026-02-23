package ticketing.domain.concert.dto;

import ticketing.domain.concert.entity.SeatStatus;

public record SeatStatusChangeRes(
        long version,
        long seatId,
        SeatStatus status
) {}