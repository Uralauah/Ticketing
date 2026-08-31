package ticketing.domain.concert.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.entity.SeatStatusOutbox;
import ticketing.domain.concert.event.SeatStatusChangedEvent;
import ticketing.domain.concert.repository.SeatStatusOutboxRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 좌석 상태 변경의 Redis 반영 (Transactional Outbox)
 * 좌석별 마지막 적용 Outbox id 이하는 건너뜀 → 커밋 직후 경로와 릴레이의 중복 적용 방지
 */
@Slf4j
@Component
public class SeatStatusPropagator {
    // 커밋 직후 경로의 유예 시간, 이보다 오래 남은 행만 릴레이가 확인
    static final Duration RELAY_GRACE = Duration.ofSeconds(3);
    private static final int RELAY_BATCH_SIZE = 500;
    // 스케줄러 스레드 장시간 점유 방지
    private static final int RELAY_MAX_BATCHES_PER_RUN = 10;

    private final SeatStatusOutboxRepository outboxRepository;
    private final SeatStatusLiveService liveService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Counter recordFailures;
    private final Counter relayed;

    public SeatStatusPropagator(SeatStatusOutboxRepository outboxRepository,
                                SeatStatusLiveService liveService,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock,
                                MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.liveService = liveService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.recordFailures = Counter.builder("ticketing.seat.status.record.failures")
                .description("커밋 직후 좌석 상태 기록(Redis)에 실패한 횟수. 릴레이가 다시 적용한다")
                .register(meterRegistry);
        this.relayed = Counter.builder("ticketing.seat.status.outbox.relayed")
                .description("커밋 직후 기록이 빠져 릴레이가 Redis에 다시 적용한 좌석 상태 변경 수")
                .register(meterRegistry);
    }

    /** 좌석 상태를 바꾼 트랜잭션 안에서 호출 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long scheduleId, long seatId, SeatStatus status) {
        SeatStatusOutbox row = outboxRepository.save(
                SeatStatusOutbox.of(scheduleId, seatId, status, LocalDateTime.now(clock))
        );
        eventPublisher.publishEvent(new SeatStatusChangedEvent(
                row.getId(),
                scheduleId,
                seatId,
                status
        ));
    }

    /**
     * afterCommit 예외는 커밋한 쪽으로 전파되므로 예외를 던지지 않음
     * 빠진 변경은 릴레이가 보충
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(SeatStatusChangedEvent event) {
        try {
            liveService.recordChange(
                    event.scheduleId(),
                    event.seatId(),
                    event.status(),
                    event.outboxId()
            );
        } catch (RuntimeException e) {
            recordFailures.increment();
            log.warn("커밋 직후 좌석 상태 기록 실패, 릴레이가 다시 적용한다: outboxId={}, scheduleId={}, seatId={}, cause={}",
                    event.outboxId(), event.scheduleId(), event.seatId(), e.toString());
        }
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.SECONDS)
    public void relayOnSchedule() {
        try {
            relayPending();
        } catch (RuntimeException e) {
            log.warn("좌석 상태 릴레이 실패, 다음 주기에 다시 시도한다: {}", e.toString());
        }
    }

    /** 유예 시간이 지난 행 중 Redis에 빠진 변경만 적용, 새로 적용한 수 반환 */
    public int relayPending() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(RELAY_GRACE);
        int applied = 0;
        for (int batch = 0; batch < RELAY_MAX_BATCHES_PER_RUN; batch++) {
            List<SeatStatusOutbox> rows = outboxRepository.findByCreatedAtLessThanEqualOrderByIdAsc(
                    cutoff,
                    Limit.of(RELAY_BATCH_SIZE)
            );
            if (rows.isEmpty()) {
                break;
            }
            applied += relay(rows);
            if (rows.size() < RELAY_BATCH_SIZE) {
                break;
            }
        }
        return applied;
    }

    private int relay(List<SeatStatusOutbox> rows) {
        List<Long> confirmed = new ArrayList<>(rows.size());
        int applied = 0;
        try {
            Map<Long, List<SeatStatusOutbox>> bySchedule = rows.stream()
                    .collect(Collectors.groupingBy(
                            SeatStatusOutbox::getScheduleId,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            for (Map.Entry<Long, List<SeatStatusOutbox>> entry : bySchedule.entrySet()) {
                long scheduleId = entry.getKey();
                List<SeatStatusOutbox> scheduleRows = entry.getValue();
                // 대부분 이미 적용된 상태 → 일정마다 HMGET 한 번으로 빠진 것만 기록
                Map<Long, Long> appliedIds = liveService.appliedOutboxIds(
                        scheduleId,
                        scheduleRows.stream().map(SeatStatusOutbox::getSeatId).distinct().toList()
                );
                for (SeatStatusOutbox row : scheduleRows) {
                    if (appliedIds.getOrDefault(row.getSeatId(), 0L) < row.getId()
                            && liveService.recordChange(
                                    scheduleId,
                                    row.getSeatId(),
                                    row.getStatus(),
                                    row.getId()
                            ) > 0) {
                        applied++;
                        relayed.increment();
                    }
                    confirmed.add(row.getId());
                }
            }
        } finally {
            // 중간에 실패해도 확인된 행까지는 삭제
            if (!confirmed.isEmpty()) {
                outboxRepository.deleteAllByIdInBatch(confirmed);
            }
        }
        if (applied > 0) {
            log.warn("커밋 직후 기록이 빠진 좌석 상태 변경 {}건을 릴레이가 적용했다", applied);
        }
        return applied;
    }
}
