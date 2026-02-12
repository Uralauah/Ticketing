package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.global.exception.CustomException;

@Service
@RequiredArgsConstructor
public class ConcertScheduleService {
    private final ConcertScheduleRepository concertScheduleRepository;

    public ConcertSchedule findById(Long scheduleId) {
        return concertScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND));
    }
}
