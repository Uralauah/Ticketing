package ticketing.domain.queue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ticketing.domain.concert.service.ScheduleValidator;
import ticketing.domain.queue.dto.QueueEntryResponse;
import ticketing.domain.queue.dto.QueueStatusResponse;
import ticketing.domain.queue.exception.QueueExceptionCode;
import ticketing.global.exception.CustomException;
import ticketing.global.queue.QueueProperties;
import ticketing.infra.redis.queue.QueueRedisRepository;
import ticketing.infra.redis.queue.QueueRedisRepository.EntryResult;
import ticketing.infra.redis.queue.QueueRedisRepository.RecentAdmissions;
import ticketing.infra.redis.queue.QueueRedisRepository.StatusResult;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {
    /** 대기 시간 예측에 쓰는 최근 입장 기록 구간 */
    public static final Duration ADMISSION_RATE_WINDOW = Duration.ofSeconds(60);

    private final QueueRedisRepository queueRepository;
    private final QueueProperties properties;
    private final ScheduleValidator scheduleValidator;
    private final Clock clock;

    /**
     * 대기 중이거나 입장한 상태면 거절, 세션이 끝난 사용자는 재진입 가능
     */
    public QueueEntryResponse enterQueue(long scheduleId, long userId) {
        // 없는 일정 ID로 대기열 키가 쌓이는 것 방지
        scheduleValidator.validateExists(scheduleId);

        long now = clock.millis();
        String token = UUID.randomUUID().toString();
        EntryResult result = queueRepository.enter(
                scheduleId,
                userId,
                token,
                now,
                properties.waitTokenTtl().toMillis(),
                now - ADMISSION_RATE_WINDOW.toMillis()
        );

        switch (result.outcome()) {
            case ALREADY_ACTIVE -> throw new CustomException(QueueExceptionCode.ALREADY_ACTIVE);
            case ALREADY_WAITING -> throw new CustomException(QueueExceptionCode.ALREADY_IN_QUEUE);
            case ENTERED -> {}
        }

        long position = result.rank() + 1;
        return new QueueEntryResponse(
                token,
                position,
                estimateWaitSeconds(position, result.admissions(), now),
                pollAfterMs(position)
        );
    }

    /** 대기 중이면 순번, 입장 상태면 만료 시각 반환 (조회 = 대기자 생존 신호) */
    public QueueStatusResponse getQueueStatus(long scheduleId, long userId, String token) {
        long now = clock.millis();
        StatusResult result = queueRepository.status(
                scheduleId,
                userId,
                token,
                now,
                now - ADMISSION_RATE_WINDOW.toMillis()
        );

        return switch (result.outcome()) {
            case ACTIVE -> QueueStatusResponse.active(result.activeExpiresAt());
            case WAITING -> {
                long position = result.rank() + 1;
                yield QueueStatusResponse.waiting(position,
                        estimateWaitSeconds(position, result.admissions(), now), pollAfterMs(position));
            }
            case INVALID_TOKEN -> throw new CustomException(QueueExceptionCode.INVALID_TOKEN);
            case NOT_IN_QUEUE -> throw new CustomException(QueueExceptionCode.SESSION_EXPIRED);
        };
    }

    /**
     * 입장 상태 확인만 하고 만료 시각은 연장하지 않음
     */
    public void validateActiveUser(long scheduleId, long userId, String token) {
        long result = queueRepository.validateActive(scheduleId, userId, token, clock.millis());
        if (result == -1) throw new CustomException(QueueExceptionCode.INVALID_TOKEN);
        if (result == -2) throw new CustomException(QueueExceptionCode.NOT_ACTIVE_USER);
    }

    public void leave(long scheduleId, long userId, String token) {
        if (!queueRepository.release(scheduleId, userId, token)) {
            throw new CustomException(QueueExceptionCode.INVALID_TOKEN);
        }
    }

    /**
     * 예약 커밋 후 입장 슬롯 반납 (대기열 1회 통과 = 예약 1건)
     * 반납 실패 시 로그만 남김 (세션 만료 때 회수)
     */
    public void releaseAfterReservation(long scheduleId, long userId, String token) {
        try {
            queueRepository.release(scheduleId, userId, token);
        } catch (RuntimeException e) {
            log.warn("예약 후 입장 슬롯 반납 실패: scheduleId={}, userId={}", scheduleId, userId, e);
        }
    }

    long estimateWaitSeconds(long position, RecentAdmissions admissions, long now) {
        double perSecond = Math.max(admissionRatePerSecond(admissions, now), 0.001);
        return (long) Math.ceil(position / perSecond);
    }

    /**
     * 최근 1분 실제 입장 인원으로 입장 속도 계산
     * 기록이 없으면 min(배치 속도, 최대 입장 인원 / 세션 시간)으로 추정
     */
    private double admissionRatePerSecond(RecentAdmissions admissions, long now) {
        long interval = properties.promoteInterval().toMillis();
        if (!admissions.isEmpty()) {
            // 오픈 직후 과소평가 방지 → 기록이 쌓인 시간으로 나눔
            long observedMs = Math.min(
                    ADMISSION_RATE_WINDOW.toMillis(),
                    Math.max(interval, now - admissions.oldestAt() + interval)
            );
            return admissions.count() * 1_000.0 / observedMs;
        }
        double batchRate = properties.promoteBatchSize() * 1_000.0 / interval;
        double turnoverRate = properties.maxActiveUsers() * 1_000.0
                / properties.activeSessionTtl().toMillis();
        return Math.min(batchRate, turnoverRate);
    }

    /** 순번이 멀수록 조회 간격 증가 */
    static long pollAfterMs(long position) {
        if (position <= 100) {
            return 1_000;
        }
        if (position <= 1_000) {
            return 3_000;
        }
        return 10_000;
    }
}
