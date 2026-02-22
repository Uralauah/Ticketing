package ticketing.domain.concert.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SeatMapRes(
        String title,
        LocalDateTime concertDate,
        List<SeatMetaRes> seats
) {}