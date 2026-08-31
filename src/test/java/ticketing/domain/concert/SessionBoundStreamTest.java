package ticketing.domain.concert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.queue.service.QueueService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.queue.QueueScheduler;
import ticketing.support.IntegrationTestSupport;
import ticketing.support.QueueTestHelper;
import ticketing.support.SseTestClient;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "ticketing.queue.active-session-ttl=2s")
@DisplayName("입장 세션 종료 시 실시간 스트림도 종료")
class SessionBoundStreamTest extends IntegrationTestSupport {

    @LocalServerPort private int port;
    @Autowired private QueueService queueService;
    @Autowired private QueueScheduler queueScheduler;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("세션(2초) 종료: 서버가 스트림을 닫고 재연결 거절")
    void 세션_종료시_스트림_종료() {
        Concert concert = concertRepository.save(Concert.builder().title("세션 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(7))
                        .build()
        );
        seatRepository.save(
                Seat.builder()
                        .concertSchedule(schedule)
                        .seatNum("A-1")
                        .price(10_000)
                        .build()
        );
        Long userId = userRepository.save(new User("viewer", 1_000_000)).getId();
        String token = QueueTestHelper.activate(
                queueService,
                queueScheduler,
                schedule.getId(),
                userId
        );
        URI stream = URI.create("http://localhost:" + port + "/api/v1/schedule/" + schedule.getId()
                + "/seat-status/stream?since=0&userId=" + userId + "&queueToken=" + token);

        try (SseTestClient client = SseTestClient.connect(stream)) {
            assertThat(client.statusCode()).isEqualTo(200);
            client.awaitEvent("ready", Duration.ofSeconds(2));

            assertThat(client.awaitClosed(Duration.ofSeconds(5))).isTrue();
        }

        try (SseTestClient reconnect = SseTestClient.connect(stream)) {
            assertThat(reconnect.statusCode()).isIn(400, 401);
        }
    }
}
