package ticketing.domain.concert.dto;

import ticketing.domain.concert.entity.ConcertSchedule;

import java.time.LocalDateTime;
import java.util.List;

public record ConcertScheduleRes(
        String title,
        LocalDateTime concertDate,
        List<SeatRes> seats
) {
    public static ConcertScheduleRes from(ConcertSchedule concertSchedule, List<SeatRes> seats){
        return new ConcertScheduleRes(concertSchedule.getConcert().getTitle(), concertSchedule.getConcertDate(), seats);
    }
}
