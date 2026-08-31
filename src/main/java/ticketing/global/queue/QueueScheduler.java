package ticketing.global.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import ticketing.domain.concert.service.SeatInventory;
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
public class QueueScheduler implements SchedulingConfigurer {
    private final QueueRedisRepository queueRepository;
    private final QueueProperties properties;
    private final SeatInventory seatInventory;
    private final Clock clock;
    private final Counter promotedCounter;
    private final Counter staleRemovedCounter;

    public QueueScheduler(QueueRedisRepository queueRepository,
                          QueueProperties properties,
                          SeatInventory seatInventory,
                          Clock clock,
                          MeterRegistry meterRegistry) {
        this.queueRepository = queueRepository;
        this.properties = properties;
        this.seatInventory = seatInventory;
        this.clock = clock;
        this.promotedCounter = Counter.builder("ticketing.queue.promoted")
                .description("대기열에서 입장으로 승격된 인원")
                .register(meterRegistry);
        this.staleRemovedCounter = Counter.builder("ticketing.queue.stale.removed")
                .description("생존 신호가 끊겨 대기열에서 정리된 인원")
                .register(meterRegistry);
    }

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
        // 매진이면 승격만 중단 (만료 세션·이탈 대기자 정리는 계속)
        long maxActive = seatInventory.isSoldOut(scheduleId) ? 0 : properties.maxActiveUsers();
        PromotionResult result = queueRepository.promote(
                scheduleId,
                maxActive,
                properties.promoteBatchSize(),
                now,
                now + properties.activeSessionTtl().toMillis(),
                now - properties.waitHeartbeatTimeout().toMillis(),
                now - QueueService.ADMISSION_RATE_WINDOW.toMillis()
        );
        promotedCounter.increment(result.promoted());
        staleRemovedCounter.increment(result.staleRemoved());
        if (result.promoted() > 0 || result.staleRemoved() > 0) {
            log.debug("대기열 승격: scheduleId={}, promoted={}, staleRemoved={}",
                    scheduleId, result.promoted(), result.staleRemoved());
        }
    }
}
