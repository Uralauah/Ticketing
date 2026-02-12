package ticketing.domain.concert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ticketing.domain.concert.entity.ConcertSchedule;

import java.util.Optional;

public interface ConcertScheduleRepository extends JpaRepository<ConcertSchedule, Long> {
    Optional<ConcertSchedule> findById(Long scheduleId);
}
