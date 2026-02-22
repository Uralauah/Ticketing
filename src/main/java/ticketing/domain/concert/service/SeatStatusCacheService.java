package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SeatStatusCacheService {

    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;

    @Cacheable(cacheNames = "seatStatus", key = "#scheduleId")
    @Transactional(readOnly = true)
    public List<SeatStatusRes> getSeatStatus(long scheduleId) {
        boolean exists = concertScheduleRepository.existsById(scheduleId);
        if (!exists) {
            throw new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND);
        }
        return seatRepository.findSeatStatusByScheduleId(scheduleId);
    }
}