package ticketing.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ticketing.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false) // 테스트에서는 지표 내보내기가 기본 비활성
@DisplayName("운영 엔드포인트")
class OperationalEndpointsTest extends IntegrationTestSupport {

    @LocalServerPort private int port;
    @LocalManagementPort private int managementPort;
    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Prometheus 지표: 관리 포트에서만 노출, 애플리케이션 고유 지표 포함")
    void Prometheus_관리_포트만_노출() {
        ResponseEntity<String> metrics = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus",
                String.class
        );

        assertThat(managementPort).isNotEqualTo(port);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody())
                .contains("ticketing_sse_connections")
                .contains("ticketing_queue_promoted_total")
                .contains("ticketing_seat_snapshot_build_seconds")
                .contains("hikaricp_connections_pending");

        assertThat(restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/prometheus",
                String.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("대기열 우회 실험용 엔드포인트: 기본 프로필에 미등록")
    void 실험용_엔드포인트_기본_미등록() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/experiment/schedule/1/seat-status/snapshot",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
