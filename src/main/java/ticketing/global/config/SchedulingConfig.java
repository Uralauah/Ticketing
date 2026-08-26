package ticketing.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 대기열 승격 등 주기 작업 (테스트에서는 enabled=false로 끄고 직접 호출) */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ticketing.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
