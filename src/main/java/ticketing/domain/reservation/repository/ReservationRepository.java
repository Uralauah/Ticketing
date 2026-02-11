package ticketing.domain.reservation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ticketing.domain.reservation.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
