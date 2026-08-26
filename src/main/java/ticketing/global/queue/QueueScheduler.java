package ticketing.global.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import ticketing.domain.queue.service.QueueService;
import ticketing.infra.redis.queue.QueueRedisRepository;
import ticketing.infra.redis.queue.QueueRedisRepository.PromotionResult;

import java.time.Clock;
import java.util.Set;

/**
 * 주기마다 빈 입장 슬롯만큼 대기자 승격
 * 인스턴스마다 실행되므로 승격 속도는 인스턴스 수만큼 빨라짐 (최대 입장 인원은 Lua로 보장)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler implements SchedulingConfigurer {
    private final QueueRedisRepository queueRepository;
    private final QueueProperties properties;
    private final Clock clock;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedRateTask(this::promoteWaitingUsers, properties.promoteInterval());
    }

    public void promoteWaitingUsers() {
        Set<String> scheduleIds = queueRepository.activeScheduleIds();
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return;
        }

        long now = clock.millis();
        for (String scheduleId : scheduleIds) {
            try {
                promote(Long.parseLong(scheduleId), now);
            } catch (RuntimeException e) {
                // 한 일정의 실패가 다른 일정 승격을 막지 않도록 함
                log.warn("대기열 승격 실패: scheduleId={}", scheduleId, e);
            }
        }
    }

    private void promote(long scheduleId, long now) {
        PromotionResult result = queueRepository.promote(
                scheduleId,
                properties.maxActiveUsers(),
                properties.promoteBatchSize(),
                now,
                now + properties.activeSessionTtl().toMillis(),
                now - properties.waitHeartbeatTimeout().toMillis(),
                now - QueueService.ADMISSION_RATE_WINDOW.toMillis());
        if (result.promoted() > 0 || result.staleRemoved() > 0) {
            log.debug("대기열 승격: scheduleId={}, promoted={}, staleRemoved={}",
                    scheduleId, result.promoted(), result.staleRemoved());
        }
    }
}
