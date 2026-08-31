package ticketing.domain.concert.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import ticketing.domain.concert.entity.SeatStatusOutbox;

import java.time.LocalDateTime;
import java.util.List;

public interface SeatStatusOutboxRepository extends JpaRepository<SeatStatusOutbox, Long> {
    // id 순서 = 좌석별 커밋 순서
    List<SeatStatusOutbox> findByCreatedAtLessThanEqualOrderByIdAsc(LocalDateTime cutoff,
                                                                    Limit limit);
}
