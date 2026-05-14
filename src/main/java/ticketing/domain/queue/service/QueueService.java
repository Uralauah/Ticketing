package ticketing.domain.queue.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ticketing.domain.queue.dto.QueueEntryResponse;
import ticketing.domain.queue.dto.QueueStatusResponse;
import ticketing.domain.queue.exception.QueueExceptionCode;
import ticketing.global.exception.CustomException;
import ticketing.global.queue.QueueProperties;

import java.time.Duration;
import java.util.UUID;

import static ticketing.global.queue.QueueProperties.ACTIVE_EVENTS_KEY;
import static ticketing.global.queue.QueueProperties.ACTIVE_QUEUE_KEY;
import static ticketing.global.queue.QueueProperties.TOKEN_KEY;
import static ticketing.global.queue.QueueProperties.WAIT_QUEUE_KEY;

@Service
@RequiredArgsConstructor
public class QueueService {
    private final StringRedisTemplate redisTemplate;

    public QueueEntryResponse enterQueue(Long eventId, Long userId) {
        String waitKey = String.format(WAIT_QUEUE_KEY, eventId);
        String tokenKey = String.format(TOKEN_KEY, eventId, userId);

        String token = UUID.randomUUID().toString();
        // SET NX로 원자적 중복 방지 (이전 get→set 패턴의 race condition 해결)
        // TTL은 현실적인 최대 대기 시간(24h)으로 설정해 대기 중 토큰 만료를 방지
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(tokenKey, token, Duration.ofHours(24));
        if (Boolean.FALSE.equals(acquired)) {
            throw new CustomException(QueueExceptionCode.ALREADY_IN_QUEUE);
        }

        try {
            double score = System.currentTimeMillis();
            redisTemplate.opsForZSet().addIfAbsent(waitKey, String.valueOf(userId), score);
            redisTemplate.opsForSet().add(ACTIVE_EVENTS_KEY, String.valueOf(eventId));

            Long rank = redisTemplate.opsForZSet().rank(waitKey, String.valueOf(userId));
            if (rank == null) {
                throw new CustomException(QueueExceptionCode.NOT_IN_QUEUE);
            }
            return new QueueEntryResponse(token, rank + 1, estimateWaitTime(rank));
        } catch (Exception e) {
            redisTemplate.delete(tokenKey);
            throw e;
        }
    }

    public QueueStatusResponse getQueueStatus(Long eventId, Long userId, String token) {
        String waitKey = String.format(WAIT_QUEUE_KEY, eventId);
        String activeKey = String.format(ACTIVE_QUEUE_KEY, eventId);
        String tokenKey = String.format(TOKEN_KEY, eventId, userId);

        String stored = redisTemplate.opsForValue().get(tokenKey);
        if (stored == null || !token.equals(stored)) {
            throw new CustomException(QueueExceptionCode.INVALID_TOKEN);
        }

        Double expiryScore = redisTemplate.opsForZSet().score(activeKey, String.valueOf(userId));
        if (expiryScore != null && expiryScore >= System.currentTimeMillis()) {
            return QueueStatusResponse.activeToken();
        }

        Long rank = redisTemplate.opsForZSet().rank(waitKey, String.valueOf(userId));
        if (rank == null) {
            // 활성 세션이 만료되고 대기열에도 없는 상태 → 재진입 필요
            throw new CustomException(QueueExceptionCode.SESSION_EXPIRED);
        }

        return new QueueStatusResponse(false, rank + 1, estimateWaitTime(rank));
    }

    public void validateActiveUser(long eventId, Long userId, String token) {
        String activeKey = String.format(ACTIVE_QUEUE_KEY, eventId);
        String tokenKey = String.format(TOKEN_KEY, eventId, userId);

        String stored = redisTemplate.opsForValue().get(tokenKey);
        if (stored == null || !token.equals(stored)) {
            throw new CustomException(QueueExceptionCode.INVALID_TOKEN);
        }

        Double expiryScore = redisTemplate.opsForZSet().score(activeKey, String.valueOf(userId));
        if (expiryScore == null || expiryScore < System.currentTimeMillis()) {
            throw new CustomException(QueueExceptionCode.NOT_ACTIVE_USER);
        }

        long newExpiry = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
        redisTemplate.opsForZSet().add(activeKey, String.valueOf(userId), newExpiry);
        redisTemplate.opsForValue().set(tokenKey, token, Duration.ofMinutes(5));
    }

    private long estimateWaitTime(Long rank) {
        // 스케줄러가 10초마다 200명씩 처리한다고 가정
        return (rank / 200) * 10;
    }
}
