package ticketing.global.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 대기열 설정, 기본값은 측정한 예약 경로 용량 기준 */
@ConfigurationProperties(prefix = "ticketing.queue")
public record QueueProperties(
        @DefaultValue("2000") long maxActiveUsers, // 승격 속도 × 입장 후 구매 소요 시간보다 크게
        @DefaultValue("200") int promoteBatchSize, // 한 번에 승격하는 최대 인원
        @DefaultValue("1s") Duration promoteInterval, // 짧게 나눠 승격해 예약 부하 분산
        @DefaultValue("5m") Duration activeSessionTtl, // 연장 없는 절대 만료
        @DefaultValue("24h") Duration waitTokenTtl, // 대기 토큰 최대 수명
        @DefaultValue("60s") Duration waitHeartbeatTimeout // 조회가 끊긴 대기자 제거 기준
) {
}
