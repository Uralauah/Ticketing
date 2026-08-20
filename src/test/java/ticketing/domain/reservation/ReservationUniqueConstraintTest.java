package ticketing.domain.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.reservation.entity.Reservation;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.exception.CustomException;
import ticketing.support.IntegrationTestSupport;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("활성 예약 UNIQUE 제약 (MySQL 8)")
class ReservationUniqueConstraintTest extends IntegrationTestSupport {

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private UserRepository userRepository;

    private Long scheduleId;
    private Seat seat;
    private User first;
    private User second;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("제약 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(7))
                        .build()
        );
        scheduleId = schedule.getId();
        seat = seatRepository.save(
                Seat.builder()
                        .concertSchedule(schedule)
                        .seatNum("A-1")
                        .price(50_000)
                        .build()
        );
        first = userRepository.save(new User("first", 1_000_000));
        second = userRepository.save(new User("second", 1_000_000));
    }

    @Test
    @DisplayName("락 없이 같은 좌석에 활성 예약 두 번 저장: DB가 두 번째 차단")
    void 같은_좌석_활성_예약_중복_차단() {
        reservationRepository.saveAndFlush(Reservation.of(first, seat));

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(Reservation.of(second, seat)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_reservation_active_seat");
        assertThat(reservationRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("취소된 예약끼리는 충돌 없음, 취소 후 같은 좌석 재예약 가능")
    void 취소_후_재예약_가능() {
        Reservation cancelled = reservationRepository.saveAndFlush(Reservation.of(first, seat));
        cancel(cancelled);
        Reservation cancelledAgain = reservationRepository.saveAndFlush(Reservation.of(second, seat));
        cancel(cancelledAgain);

        assertThatNoException()
                .isThrownBy(() -> reservationRepository.saveAndFlush(Reservation.of(first, seat)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE active_seat_id = ?",
                Long.class,
                seat.getId()
        )).isEqualTo(1L);
    }

    @Test
    @DisplayName("예약 서비스 경로의 제약 위반: 409(SEAT_NOT_AVAILABLE)로 변환, 좌석 상태 변경 롤백")
    void 제약_위반_409_변환_롤백() {
        // 좌석 상태(AVAILABLE)는 그대로 두고 예약만 직접 넣어 락을 우회한 쓰기 경로 재현
        reservationRepository.saveAndFlush(Reservation.of(first, seat));

        assertThatThrownBy(() -> reservationService.reserve(second.getId(), scheduleId, seat.getId()))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getExceptionCode())
                                .isEqualTo(SeatExceptionCode.SEAT_NOT_AVAILABLE));
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    private void cancel(Reservation reservation) {
        // 취소 유스케이스가 아직 없어 상태만 직접 변경
        jdbcTemplate.update(
                "UPDATE reservation SET status = 'CANCELLED' WHERE id = ?",
                reservation.getId()
        );
    }
}
