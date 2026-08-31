package ticketing.domain.queue.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("매진 시 대기열 종료")
class SoldOutQueueTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private QueueService queueService;
    @Autowired private QueueScheduler queueScheduler;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;

    private Long scheduleId;
    private Long seatId;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("한 자리 공연").build());
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
    @DisplayName("마지막 좌석 판매: 대기자는 다음 조회에서 매진 확인, 새 진입 거절, 승격 없음")
    void 매진시_대기열_종료() throws Exception {
        Long buyer = userRepository.save(new User("buyer", 1_000_000)).getId();
        Long waiting = userRepository.save(new User("waiting", 1_000_000)).getId();
        Long late = userRepository.save(new User("late", 1_000_000)).getId();
        String buyerToken = QueueTestHelper.activate(
                queueService,
                queueScheduler,
                scheduleId,
                buyer
        );
        String waitingToken = queueService.enterQueue(scheduleId, waiting).token();

        mockMvc.perform(post("/api/v1/reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Queue-Token", buyerToken)
                        .content("{\"userId\":" + buyer + ",\"scheduleId\":" + scheduleId
                                + ",\"seatId\":" + seatId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/queue/{id}/status", scheduleId)
                        .param("userId", waiting.toString())
                        .header("X-Queue-Token", waitingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldOut").value(true))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.pollAfterMs").value(0));

        mockMvc.perform(post("/api/v1/queue/{id}/entry", scheduleId)
                        .param("userId", late.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("QUEUE-410-002"));

        queueScheduler.promoteWaitingUsers();
        assertThat(queueService.getQueueStatus(scheduleId, waiting, waitingToken).active())
                .isFalse();
    }
}
