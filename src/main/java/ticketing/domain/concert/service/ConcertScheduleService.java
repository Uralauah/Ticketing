package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketing.domain.concert.dto.ConcertScheduleRes;
import ticketing.domain.concert.dto.SeatRes;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConcertScheduleService {
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;

    @Cacheable(cacheNames = "scheduleSeats", key = "#scheduleId")
    @Transactional(readOnly = true)
    public ConcertScheduleRes getSeats(long scheduleId) {
        ConcertSchedule schedule = concertScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND));

        List<SeatRes> seats = seatRepository.findAllByConcertScheduleId(scheduleId);

        return ConcertScheduleRes.from(schedule, seats);
    }
}
