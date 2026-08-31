package ticketing.domain.concert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.support.IntegrationTestSupport;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles({"test", "perf"})
// 10만 건 적재는 LoadTestDataSeederTest에서 따로 검증
@TestPropertySource(properties = "ticketing.seed.load-test-data=false")
@DisplayName("perf 프로필의 비교 측정용 엔드포인트")
class SeatStatusExperimentEndpointTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;

    private Long scheduleId;
    private Long seatId;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("실험 공연").build());
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
    @DisplayName("예약 경로 용량 측정용: 대기열 토큰 없이 같은 예약 경로 사용")
    void 대기열_없이_예약() throws Exception {
        Long userId = userRepository.save(new User("perf", 100_000)).getId();
        String body = "{\"userId\":" + userId + ",\"scheduleId\":" + scheduleId
                + ",\"seatId\":" + seatId + "}";

        mockMvc.perform(post("/api/v1/experiment/reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(post("/api/v1/experiment/reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("대기열 없이 캐시 스냅샷과 legacy 스냅샷을 같은 내용으로 반환")
    void 캐시_legacy_스냅샷_동일() throws Exception {
        mockMvc.perform(get("/api/v1/experiment/schedule/{id}/seat-status/snapshot", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
        mockMvc.perform(get("/api/v1/experiment/schedule/{id}/seat-status/snapshot/legacy", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(0))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    }
}
