package ticketing.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ticketing.domain.concert.service.SeatStatusSseBroker;

/** 애플리케이션 고유 지표 (기본 지표는 actuator 제공) */
@Configuration
public class MetricsConfig {
    @Bean
    public MeterBinder sseConnectionMetrics(SeatStatusSseBroker broker) {
        return registry -> Gauge
                .builder("ticketing.sse.connections", broker, SeatStatusSseBroker::connectionCount)
                .description("열려 있는 좌석 상태 SSE 연결 수")
                .register(registry);
    }
}
