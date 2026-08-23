package ticketing.domain.concert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.queue.service.QueueService;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;
import ticketing.global.queue.QueueScheduler;
import ticketing.support.IntegrationTestSupport;
import ticketing.support.QueueTestHelper;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("좌석 상태 스냅샷 API (ETag, gzip)")
class SeatSnapshotApiTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private QueueService queueService;
    @Autowired private QueueScheduler queueScheduler;
    @Autowired private ReservationService reservationService;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private UserRepository userRepository;

    private Long scheduleId;
    private Long seatId;
    private Long userId;
    private String token;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("스냅샷 테스트 공연").build());
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
        userId = userRepository.save(new User("viewer", 1_000_000)).getId();
        token = QueueTestHelper.activate(queueService, queueScheduler, scheduleId, userId);
    }

    @Test
    @DisplayName("ETag 일치 시 본문 없이 304, 좌석 변경 시 새 ETag·새 상태 반환")
    void ETag_조건부_요청() throws Exception {
        MvcResult first = mockMvc.perform(snapshotRequest())
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "W/\"0\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andReturn();
        assertThat(seatStatus(first.getResponse().getContentAsByteArray())).isEqualTo("AVAILABLE");

        mockMvc.perform(snapshotRequest().header(HttpHeaders.IF_NONE_MATCH, "W/\"0\""))
                .andExpect(status().isNotModified())
                .andExpect(content().bytes(new byte[0]));

        reservationService.reserve(userId, scheduleId, seatId);

        MvcResult changed = mockMvc.perform(snapshotRequest()
                        .header(HttpHeaders.IF_NONE_MATCH, "W/\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "W/\"1\""))
                .andReturn();
        assertThat(seatStatus(changed.getResponse().getContentAsByteArray())).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("gzip 수용 클라이언트: 미리 압축한 본문 반환")
    void gzip_수용시_압축_본문_반환() throws Exception {
        MvcResult result = mockMvc.perform(snapshotRequest()
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING))
                .andReturn();

        byte[] body;
        try (GZIPInputStream in = new GZIPInputStream(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray())
        )) {
            body = in.readAllBytes();
        }
        assertThat(seatStatus(body)).isEqualTo("AVAILABLE");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder snapshotRequest() {
        return get("/api/v1/schedule/{scheduleId}/seat-status/snapshot/{userId}", scheduleId, userId)
                .header("X-Queue-Token", token);
    }

    private String seatStatus(byte[] json) throws Exception {
        JsonNode body = objectMapper.readTree(json);
        return body.get("seats").get(0).get("status").asText();
    }
}
