package ticketing.domain.concert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.concert.service.SeatInventory;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.concert.service.SeatStatusPropagator;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.infra.redis.seatstatus.SeatStatusRedisKeys;
import ticketing.support.IntegrationTestSupport;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

@DisplayName("좌석 상태 Outbox 릴레이")
class SeatStatusOutboxRelayTest extends IntegrationTestSupport {

    @MockitoSpyBean private SeatStatusLiveService liveService;

    @Autowired private SeatStatusPropagator propagator;
    @Autowired private SeatInventory seatInventory;
    @Autowired private ReservationService reservationService;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;

    private long scheduleId;
    private long seatId;
    private long userId;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("릴레이 테스트 공연").build());
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
        userId = userRepository.save(new User("buyer", 1_000_000)).getId();
        liveService.ensureInitialized(scheduleId);
    }

    @Test
    @DisplayName("커밋 직후 Redis 기록 실패: 릴레이가 재적용해 예약 상태·매진 반영")
    void 커밋_후_누락된_변경_재적용() {
        failNextRecord();
        reservationService.reserve(userId, scheduleId, seatId);
        assertThat(redisStatus()).isEqualTo("AVAILABLE");
        assertThat(seatInventory.isSoldOut(scheduleId)).isFalse();

        ageOutboxRows();
        assertThat(propagator.relayPending()).isEqualTo(1);

        assertThat(redisStatus()).isEqualTo("RESERVED");
        assertThat(seatInventory.isSoldOut(scheduleId)).isTrue();
        assertThat(outboxRows()).isZero();
    }

    @Test
    @DisplayName("이미 반영된 변경: 재발행 없이 Outbox 행만 삭제")
    void 반영된_변경_행만_삭제() {
        reservationService.reserve(userId, scheduleId, seatId);
        long version = liveService.snapshot(scheduleId).currentVersion();

        ageOutboxRows();
        assertThat(propagator.relayPending()).isZero();

        assertThat(liveService.snapshot(scheduleId).currentVersion()).isEqualTo(version);
        assertThat(outboxRows()).isZero();
    }

    @Test
    @DisplayName("유예 시간 전 행: 커밋 직후 경로에 맡기고 미처리")
    void 유예_시간_전_행_유지() {
        failNextRecord();
        reservationService.reserve(userId, scheduleId, seatId);

        assertThat(propagator.relayPending()).isZero();

        assertThat(redisStatus()).isEqualTo("AVAILABLE");
        assertThat(outboxRows()).isEqualTo(1);
    }

    private void failNextRecord() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .doCallRealMethod()
                .when(liveService).recordChange(anyLong(), anyLong(), any(), anyLong());
    }

    private void ageOutboxRows() {
        jdbcTemplate.update("UPDATE seat_status_outbox SET created_at = created_at - INTERVAL 1 MINUTE");
    }

    private long outboxRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_status_outbox", Long.class);
    }

    private Object redisStatus() {
        return redisTemplate.opsForHash().get(
                SeatStatusRedisKeys.hash(scheduleId),
                String.valueOf(seatId)
        );
    }
}
