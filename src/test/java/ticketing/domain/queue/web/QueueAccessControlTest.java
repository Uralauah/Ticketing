package ticketing.domain.queue.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.queue.service.QueueService;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.queue.QueueScheduler;
import ticketing.infra.redis.queue.QueueKeys;
import ticketing.support.IntegrationTestSupport;
import ticketing.support.QueueTestHelper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("대기열 통과 검증 (좌석 상태 API, 예약 API)")
class QueueAccessControlTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private QueueService queueService;
    @Autowired private QueueScheduler queueScheduler;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReservationRepository reservationRepository;

    private Long scheduleId;
    private Long seatId;
    private Long activeUserId;
    private String activeToken;
    private Long waitingUserId;
    private String waitingToken;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("접근 제어 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(7))
                        .build()
        );
        scheduleId = schedule.getId();
        seatId = seatRepository.save(Seat.builder().concertSchedule(schedule).seatNum("A-1").price(10_000).build()).getId();

        activeUserId = userRepository.save(new User("active", 1_000_000)).getId();
        activeToken = QueueTestHelper.activate(
                queueService,
                queueScheduler,
                scheduleId,
                activeUserId
        );
        // 승격을 다시 돌리지 않으므로 대기 상태로 유지
        waitingUserId = userRepository.save(new User("waiting", 1_000_000)).getId();
        waitingToken = queueService.enterQueue(scheduleId, waitingUserId).token();
    }

    @Test
    @DisplayName("좌석 스냅샷: userId·토큰 누락 시 400, 토큰 불일치 401, 대기 중 400, 입장 후 200")
    void 스냅샷_입장_사용자만_허용() throws Exception {
        mockMvc.perform(get(seatStatus("snapshot")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUEUE-400-002"));
        mockMvc.perform(get(seatStatus("snapshot"))
                        .param("userId", activeUserId.toString())
                        .header("X-Queue-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("QUEUE-401-001"));
        mockMvc.perform(asUser(get(seatStatus("snapshot")), waitingUserId, waitingToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUEUE-400-001"));
        mockMvc.perform(asUser(get(seatStatus("snapshot")), activeUserId, activeToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("변경분 조회도 대기열 통과 필요")
    void 변경분_입장_사용자만_허용() throws Exception {
        mockMvc.perform(get(seatStatus("delta")).param("since", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asUser(
                        get(seatStatus("delta")).param("since", "0"),
                        activeUserId,
                        activeToken
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(0));
    }

    @Test
    @DisplayName("쿼리 파라미터 토큰은 SSE 스트림에서만 허용 (URL은 로그에 남을 수 있음)")
    void 쿼리_토큰_스트림만_허용() throws Exception {
        mockMvc.perform(get(seatStatus("snapshot"))
                        .param("userId", activeUserId.toString())
                        .param("queueToken", activeToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUEUE-400-002"));
    }

    @Test
    @DisplayName("대기 중 사용자: 예약 API를 직접 호출해도 예약 불가")
    void 대기중_사용자_예약_불가() throws Exception {
        mockMvc.perform(reserve(waitingUserId, waitingToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUEUE-400-001"));

        assertThat(reservationRepository.count()).isZero();
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("다른 사용자의 userId로 예약 불가 (토큰·userId 함께 검증)")
    void 다른_사용자로_예약_불가() throws Exception {
        mockMvc.perform(reserve(waitingUserId, activeToken))
                .andExpect(status().isUnauthorized());
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    @DisplayName("토큰 헤더 누락: 400")
    void 토큰_헤더_누락_400() throws Exception {
        mockMvc.perform(post("/api/v1/reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(activeUserId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
    }

    @Test
    @DisplayName("예약 성공: 입장 슬롯 반납으로 다음 대기자 입장, 같은 토큰 재사용 불가")
    void 예약_성공시_슬롯_반납() throws Exception {
        mockMvc.perform(reserve(activeUserId, activeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatId").value(seatId))
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(redisTemplate.opsForZSet().score(
                QueueKeys.active(scheduleId),
                activeUserId.toString()
        )).isNull();
        mockMvc.perform(asUser(get(seatStatus("snapshot")), activeUserId, activeToken))
                .andExpect(status().isUnauthorized());

        queueScheduler.promoteWaitingUsers();
        assertThat(queueService.getQueueStatus(scheduleId, waitingUserId, waitingToken).active())
                .isTrue();
    }

    private String seatStatus(String path) {
        return "/api/v1/schedule/" + scheduleId + "/seat-status/" + path;
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request,
                                                        Long userId,
                                                        String token) {
        return request.param("userId", userId.toString()).header("X-Queue-Token", token);
    }

    private MockHttpServletRequestBuilder reserve(Long userId, String token) {
        return post("/api/v1/reservation")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Queue-Token", token)
                .content(reservationBody(userId));
    }

    private String reservationBody(Long userId) {
        return "{\"userId\":" + userId + ",\"scheduleId\":" + scheduleId
                + ",\"seatId\":" + seatId + "}";
    }
}
