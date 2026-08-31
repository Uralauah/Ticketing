package ticketing.domain.reservation;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.reservation.dto.ReservationRes;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.support.IntegrationTestSupport;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("예약 커밋 후 좌석 상태 기록")
class SeatStatusRecordAfterCommitTest extends IntegrationTestSupport {

    @MockitoBean private SeatStatusLiveService seatStatusLiveService;

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MeterRegistry meterRegistry;

    private Long scheduleId;
    private Long seatId;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("기록 실패 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(7))
                        .build()
        );
        scheduleId = schedule.getId();
        seatId = seatRepository.save(
                Seat.builder()
                        .concertSchedule(schedule)
                        .seatNum("A-1")
                        .price(10_000)
                        .build()
        ).getId();
    }

    @Test
    @DisplayName("커밋 후 Redis 기록 실패: 이미 확정된 예약은 성공 응답")
    void 커밋_후_기록_실패해도_예약_성공() {
        Long userId = userRepository.save(new User("buyer", 1_000_000)).getId();
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(seatStatusLiveService).recordChange(anyLong(), anyLong(), any(), anyLong());
        double failuresBefore = failures();

        ReservationRes reservation = reservationService.reserve(userId, scheduleId, seatId);

        assertThat(reservation.reservationId()).isNotNull();
        assertThat(reservationRepository.count()).isEqualTo(1L);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.RESERVED);
        assertThat(failures()).isEqualTo(failuresBefore + 1);
        // 빠진 변경은 Outbox에 남아 릴레이가 재적용 (SeatStatusOutboxRelayTest)
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("롤백된 예약: 좌석 상태 기록·전파 없음, Outbox 행 미생성")
    void 롤백된_예약_기록_안함() {
        Long poorUserId = userRepository.save(new User("poor", 0)).getId();

        assertThatThrownBy(() -> reservationService.reserve(poorUserId, scheduleId, seatId));

        verify(seatStatusLiveService, never()).recordChange(anyLong(), anyLong(), any(), anyLong());
        assertThat(outboxRows()).isZero();
    }

    private long outboxRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_status_outbox", Long.class);
    }

    private double failures() {
        return meterRegistry.counter("ticketing.seat.status.record.failures").count();
    }
}
