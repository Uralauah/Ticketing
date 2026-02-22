package ticketing.domain.concert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ticketing.domain.concert.entity.ConcertSchedule;

import java.util.Optional;

public interface ConcertScheduleRepository extends JpaRepository<ConcertSchedule, Long> {
    @Query("""
                select cs
                from ConcertSchedule cs
                join fetch cs.concert c
                where cs.id = :id
            """)
    Optional<ConcertSchedule> findByIdWithConcert(@Param("id") long id);
}
