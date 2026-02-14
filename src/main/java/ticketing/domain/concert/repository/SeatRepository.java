package ticketing.domain.concert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ticketing.domain.concert.entity.Seat;

import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
}
