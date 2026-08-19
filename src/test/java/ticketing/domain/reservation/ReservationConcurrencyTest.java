package ticketing.domain.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.exception.CustomException;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;
import ticketing.support.ConcurrentRunner;
import ticketing.support.IntegrationTestSupport;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("예약 동시성 테스트 (MySQL 8 + Redis 7)")
class ReservationConcurrencyTest extends IntegrationTestSupport {

    private static final int THREAD_COUNT = 10;

    @Autowired private ReservationService        reservationService;
    @Autowired private ReservationRepository     reservationRepository;
    @Autowired private SeatRepository            seatRepository;
    @Autowired private ConcertRepository         concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private UserRepository            userRepository;
    @Autowired private SeatStatusRedisRepository seatStatusRedisRepository;

    private Long scheduleId;
    private Long seatId;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("동시성 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(30))
                        .build()
        );
        scheduleId = schedule.getId();

        seatId = seatRepository.save(
                Seat.builder()
                        .concertSchedule(schedule)
                        .seatNum("A-1")
                        .price(50_000)
                        .build()
        ).getId();

        userIds.clear();
        for (int i = 1; i <= THREAD_COUNT; i++) {
            userIds.add(userRepository.save(new User("User_" + i, 1_000_000)).getId());
        }
    }

    @Test
    @DisplayName("같은 좌석 동시 예약 10건: 1건만 성공, 나머지 SEAT_NOT_AVAILABLE 예외")
    void 동시_예약_1건만_성공() throws InterruptedException {
        ConcurrentRunner.Result result = ConcurrentRunner.run(
                THREAD_COUNT,
                i -> reservationService.reserve(userIds.get(i), scheduleId, seatId)
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failures())
                .hasSize(THREAD_COUNT - 1)
                .allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                        CustomException.class,
                        ce -> assertThat(ce.getExceptionCode())
                                .isEqualTo(SeatExceptionCode.SEAT_NOT_AVAILABLE)
                ));
        assertThat(reservationRepository.count()).isEqualTo(1L);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.RESERVED);
    }

    @Test
    @DisplayName("예약 커밋 시 Redis 좌석 상태·버전 반영")
    void 커밋_후_Redis_반영() {
        reservationService.reserve(userIds.get(0), scheduleId, seatId);

        assertThat(seatStatusRedisRepository.getAllStatuses(scheduleId))
                .containsEntry(String.valueOf(seatId), SeatStatus.RESERVED.name());
        assertThat(seatStatusRedisRepository.currentVersion(scheduleId)).isEqualTo(1L);
    }
}
