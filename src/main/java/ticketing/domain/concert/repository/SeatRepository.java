package ticketing.domain.concert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ticketing.domain.concert.dto.SeatRes;
import ticketing.domain.concert.entity.Seat;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("""
                    select new ticketing.domain.concert.dto.SeatRes(s.seatNum, s.price, s.status)
              from Seat s
              where s.concertSchedule.id = :scheduleId
            """)
    List<SeatRes> findAllByConcertScheduleId(Long scheduleId);
}
