package ticketing.domain.concert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.support.IntegrationTestSupport;
import ticketing.support.SseTestClient;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("좌석 변경 실시간 전파 (예약 커밋 → Redis PUBLISH → 구독 → SSE)")
class SeatStatusRealtimeIntegrationTest extends IntegrationTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort private int port;

    @Autowired private ReservationService reservationService;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private UserRepository userRepository;

    private Long scheduleId;
    private Long userId;
    private final List<Long> seatIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("실시간 전파 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(7))
                        .build()
        );
        scheduleId = schedule.getId();
        seatIds.clear();
        for (int i = 1; i <= 3; i++) {
            seatIds.add(seatRepository.save(
                    Seat.builder()
                            .concertSchedule(schedule)
                            .seatNum("A-" + i)
                            .price(10_000)
                            .build()
            ).getId());
        }
        userId = userRepository.save(new User("listener", 1_000_000)).getId();
    }

    @Test
    @DisplayName("연결된 클라이언트는 예약 커밋 직후 좌석 변경 이벤트를 version 순서대로 받는다")
    void reservationsArePushedInVersionOrder() {
        try (SseTestClient client = SseTestClient.connect(streamUri())) {
            assertThat(client.statusCode()).isEqualTo(200);
            client.awaitEvent("ready", TIMEOUT);

            for (Long seatId : seatIds) {
                reservationService.reserve(userId, scheduleId, seatId);
            }

            for (int i = 0; i < seatIds.size(); i++) {
                SseTestClient.Event event = client.awaitEvent("seat-status", TIMEOUT);
                assertThat(event.id()).isEqualTo(String.valueOf(i + 1));
                assertThat(event.data())
                        .contains("\"seatId\":" + seatIds.get(i))
                        .contains("\"status\":\"RESERVED\"");
            }
        }
    }

    private URI streamUri() {
        return URI.create("http://localhost:" + port + "/api/v1/schedule/" + scheduleId + "/seat-status/stream?since=0");
    }
}
