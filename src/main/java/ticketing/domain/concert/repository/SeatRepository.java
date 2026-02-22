package ticketing.domain.concert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ticketing.domain.concert.dto.SeatMetaRes;
import org.springframework.data.repository.query.Param;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.entity.Seat;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    @Query("""
                select new ticketing.domain.concert.dto.SeatMetaRes(s.id, s.seatNum, s.price)
                from Seat s
                where s.concertSchedule.id = :scheduleId
                order by s.seatNum
            """)
    List<SeatMetaRes> findSeatMetaByScheduleId(@Param("scheduleId") long scheduleId);

    @Query("""
                select new ticketing.domain.concert.dto.SeatStatusRes(s.id, s.status)
                from Seat s
                where s.concertSchedule.id = :scheduleId
                order by s.id
            """)
    List<SeatStatusRes> findSeatStatusByScheduleId(@Param("scheduleId") long scheduleId);
}