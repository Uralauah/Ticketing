package ticketing.domain.concert.dto;

import ticketing.domain.concert.entity.SeatStatus;

public record SeatStatusChangeRes(
        long version,
        long seatId,
        SeatStatus status,
        long publishedAt // 발행 시각(epoch ms), 전파 지연 측정용
) {}
