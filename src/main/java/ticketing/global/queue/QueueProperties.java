package ticketing.global.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 대기열 설정, 기본값은 기존 측정 조건(활성 1,000명, 10초마다 200명 승격, 세션 5분)
 *
 * @param maxActiveUsers       동시에 입장할 수 있는 최대 인원
 * @param promoteBatchSize     한 번에 승격하는 최대 인원
 * @param promoteInterval      승격 주기
 * @param activeSessionTtl     입장 후 머무를 수 있는 시간 (연장 없음)
 * @param waitTokenTtl         대기 중 토큰의 최대 수명
 * @param waitHeartbeatTimeout 조회가 끊긴 대기자 제거 기준
 */
@ConfigurationProperties(prefix = "ticketing.queue")
public record QueueProperties(
        @DefaultValue("1000") long maxActiveUsers,
        @DefaultValue("200") int promoteBatchSize,
        @DefaultValue("10s") Duration promoteInterval,
        @DefaultValue("5m") Duration activeSessionTtl,
        @DefaultValue("24h") Duration waitTokenTtl,
        @DefaultValue("60s") Duration waitHeartbeatTimeout
) {
}
