package ticketing.domain.concert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ticketing.domain.concert.entity.Concert;

public interface ConcertRepository extends JpaRepository<Concert, Long> {
}
