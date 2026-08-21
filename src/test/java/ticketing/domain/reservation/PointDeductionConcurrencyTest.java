package ticketing.domain.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.exception.UserExceptionCode;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.exception.CustomException;
import ticketing.support.ConcurrentRunner;
import ticketing.support.IntegrationTestSupport;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("포인트 차감 정합성 (MySQL 8)")
class PointDeductionConcurrencyTest extends IntegrationTestSupport {

    private static final int SEAT_COUNT = 10;
    private static final int PRICE = 50_000;
    private static final int INITIAL_POINT = 300_000; // 6석까지만 구매 가능

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private UserRepository userRepository;

    private Long scheduleId;
    private Long userId;
    private final List<Long> seatIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("포인트 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(7))
                        .build()
        );
        scheduleId = schedule.getId();

        seatIds.clear();
        for (int i = 1; i <= SEAT_COUNT; i++) {
            seatIds.add(seatRepository.save(
                    Seat.builder()
                            .concertSchedule(schedule)
                            .seatNum("A-" + i)
                            .price(PRICE)
                            .build()
            ).getId());
        }
        userId = userRepository.save(new User("buyer", INITIAL_POINT)).getId();
    }

    @Test
    @DisplayName("같은 사용자의 서로 다른 좌석 10개 동시 예약: 포인트 차감 유실 없음")
    void 동시_예약_차감_유실_없음() throws InterruptedException {
        ConcurrentRunner.Result result = ConcurrentRunner.run(
                SEAT_COUNT,
                i -> reservationService.reserve(userId, scheduleId, seatIds.get(i))
        );

        int affordable = INITIAL_POINT / PRICE;
        assertThat(result.successCount()).isEqualTo(affordable);
        assertThat(result.failures())
                .hasSize(SEAT_COUNT - affordable)
                .allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                        CustomException.class,
                        ce -> assertThat(ce.getExceptionCode())
                                .isEqualTo(UserExceptionCode.USER_CANT_PAY)
                ));

        int finalPoint = userRepository.findById(userId).orElseThrow().getPoint();
        assertThat(finalPoint).isEqualTo(INITIAL_POINT - PRICE * result.successCount());
        assertThat(reservationRepository.count()).isEqualTo(result.successCount());
        // 잔액 부족으로 실패한 예약은 좌석 상태 변경까지 롤백
        assertThat(seatRepository.findAllById(seatIds))
                .filteredOn(seat -> seat.getStatus() == SeatStatus.RESERVED)
                .hasSize(result.successCount());
    }

    @Test
    @DisplayName("없는 사용자: USER_NOT_FOUND 예외, 좌석은 AVAILABLE 유지")
    void 없는_사용자_예외_좌석_유지() {
        long unknownUserId = userId + 1_000;

        assertThatThrownBy(() -> reservationService.reserve(unknownUserId, scheduleId, seatIds.get(0)))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getExceptionCode())
                                .isEqualTo(UserExceptionCode.USER_NOT_FOUND));
        assertThat(seatRepository.findById(seatIds.get(0)).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }
}
