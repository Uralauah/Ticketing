package ticketing.domain.concert;

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
import ticketing.support.IntegrationTestSupport;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("좌석 배치도 API")
class SeatMapApiTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;

    private Long scheduleId;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.builder().title("배치도 테스트 공연").build());
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.of(2026, 9, 1, 19, 0))
                        .build()
        );
        scheduleId = schedule.getId();
        seatRepository.save(
                Seat.builder()
                        .concertSchedule(schedule)
                        .seatNum("B-1")
                        .price(99_000)
                        .build()
        );
        seatRepository.save(
                Seat.builder()
                        .concertSchedule(schedule)
                        .seatNum("A-1")
                        .price(150_000)
                        .build()
        );
    }

    @Test
    @DisplayName("대기열 없이 공개, 공유 캐시 가능, ETag 일치 시 304")
    void 배치도_공개_캐시() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/schedule/{id}/seat-map", scheduleId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
                .andExpect(jsonPath("$.title").value("배치도 테스트 공연"))
                .andExpect(jsonPath("$.concertDate").value("2026-09-01T19:00:00"))
                .andExpect(jsonPath("$.seats[0].seatNum").value("A-1"))
                .andExpect(jsonPath("$.seats[0].price").value(150_000))
                .andExpect(jsonPath("$.seats[1].seatNum").value("B-1"))
                .andReturn();

        String eTag = result.getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(get("/api/v1/schedule/{id}/seat-map", scheduleId)
                        .header(HttpHeaders.IF_NONE_MATCH, eTag))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("없는 일정: 404")
    void 없는_일정_404() throws Exception {
        mockMvc.perform(get("/api/v1/schedule/{id}/seat-map", scheduleId + 1_000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE-404-001"));
    }
}
