package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketing.domain.concert.dto.SeatMapRes;
import ticketing.domain.concert.dto.SeatMetaRes;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConcertScheduleQueryService {

    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;

    @Cacheable(cacheNames = "seatMap", key = "#scheduleId")
    @Transactional(readOnly = true)
    public SeatMapRes getSeatMap(long scheduleId) {
        ConcertSchedule schedule = concertScheduleRepository.findByIdWithConcert(scheduleId)
                .orElseThrow(() -> new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND));

        List<SeatMetaRes> seats = seatRepository.findSeatMetaByScheduleId(scheduleId);

        return new SeatMapRes(
                schedule.getConcert().getTitle(),
                schedule.getConcertDate(),
                seats
        );
    }
}