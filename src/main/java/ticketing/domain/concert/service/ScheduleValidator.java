package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.global.exception.CustomException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 일정 존재 확인, 삭제 기능이 없어 확인된 일정은 로컬에 캐시 */
@Component
@RequiredArgsConstructor
public class ScheduleValidator {
    private final ConcertScheduleRepository concertScheduleRepository;
    private final Set<Long> knownSchedules = ConcurrentHashMap.newKeySet();

    public void validateExists(long scheduleId) {
        if (knownSchedules.contains(scheduleId)) {
            return;
        }
        if (!concertScheduleRepository.existsById(scheduleId)) {
            throw new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND);
        }
        knownSchedules.add(scheduleId);
    }
}
