package ticketing.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ticketing.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("오류 응답")
class ErrorResponseTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("필수 헤더 누락: 500 대신 400과 누락된 헤더 이름 반환")
    void 필수_헤더_누락_400() throws Exception {
        mockMvc.perform(get("/api/v1/queue/1/status").param("userId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"))
                .andExpect(jsonPath("$.message").value(containsString("X-Queue-Token")));
    }

    @Test
    @DisplayName("경로 변수 형식 오류: 400")
    void 경로_변수_형식_오류_400() throws Exception {
        mockMvc.perform(get("/api/v1/queue/abc/status")
                        .param("userId", "1")
                        .header("X-Queue-Token", "t"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
    }

    @Test
    @DisplayName("예약 요청 본문 필수 값 누락: 400과 해당 필드 반환")
    void 요청_본문_필수값_누락_400() throws Exception {
        mockMvc.perform(post("/api/v1/reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("scheduleId")))
                .andExpect(jsonPath("$.message").value(containsString("seatId")));
    }

    @Test
    @DisplayName("JSON 형식 오류: 400")
    void JSON_형식_오류_400() throws Exception {
        mockMvc.perform(post("/api/v1/reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
    }

    @Test
    @DisplayName("없는 경로: 404, 미지원 메서드: 405")
    void 없는_경로_404_미지원_메서드_405() throws Exception {
        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GLOBAL-404-001"));
        mockMvc.perform(put("/api/v1/queue/1/entry").param("userId", "1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("GLOBAL-405-001"));
    }

    @Test
    @DisplayName("SSE 요청 오류도 원래 상태 코드와 JSON 본문으로 응답")
    void SSE_요청_오류_JSON_응답() throws Exception {
        mockMvc.perform(get("/api/v1/schedule/1/seat-status/stream")
                        .param("since", "0")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("QUEUE-400-002"));
    }
}
