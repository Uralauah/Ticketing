package ticketing.infra.redis.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 대기열 상태 전이를 Lua로 원자 처리 (요청당 Redis 왕복 1회) */
@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {
    // 승격 1회당 정리할 최대 이탈 대기자 수
    private static final int STALE_SCAN_LIMIT = 1_000;

    private final StringRedisTemplate redis;

    // 최근 입장 인원 합계와 가장 오래된 기록 시각
    private static final String RECENT_ADMISSIONS_FUNCTION = """
            local function recentAdmissions(key, windowStart)
              local entries = redis.call('ZRANGEBYSCORE', key, windowStart, '+inf', 'WITHSCORES')
              local total, oldest = 0, -1
              for i = 1, #entries, 2 do
                total = total + tonumber(string.match(entries[i], ':(%d+)$'))
                if oldest < 0 then oldest = tonumber(entries[i + 1]) end
              end
              return total, oldest
            end
            """;

    private static final DefaultRedisScript<List> ENTER_SCRIPT = script(RECENT_ADMISSIONS_FUNCTION + """
            -- KEYS: 1=wait 2=active 3=token 4=seen 5=seq 6=activeSchedules 7=admissions
            -- ARGV: 1=userId 2=token 3=nowMs 4=tokenTtlMs 5=scheduleId 6=admissionWindowStartMs
            local expiry = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if expiry and tonumber(expiry) >= tonumber(ARGV[3]) then return {-1, 0, 0, -1} end -- 이미 입장
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) then return {-2, 0, 0, -1} end           -- 이미 대기 중

            -- 세션이 끝났거나 정리된 사용자는 토큰을 덮어쓰고 재진입
            redis.call('ZREM', KEYS[2], ARGV[1])
            local seq = redis.call('INCR', KEYS[5])
            redis.call('ZADD', KEYS[1], seq, ARGV[1])
            redis.call('ZADD', KEYS[4], ARGV[3], ARGV[1])
            redis.call('SET', KEYS[3], ARGV[2], 'PX', ARGV[4])
            redis.call('SADD', KEYS[6], ARGV[5])

            local admitted, oldest = recentAdmissions(KEYS[7], ARGV[6])
            return {0, redis.call('ZRANK', KEYS[1], ARGV[1]), admitted, oldest}
            """);

    private static final DefaultRedisScript<List> STATUS_SCRIPT = script(RECENT_ADMISSIONS_FUNCTION + """
            -- KEYS: 1=token 2=active 3=wait 4=seen 5=admissions
            -- ARGV: 1=token 2=userId 3=nowMs 4=admissionWindowStartMs
            local stored = redis.call('GET', KEYS[1])
            if not stored or stored ~= ARGV[1] then return {-1, 0, 0, -1} end                 -- 토큰 불일치

            local expiry = redis.call('ZSCORE', KEYS[2], ARGV[2])
            if expiry and tonumber(expiry) >= tonumber(ARGV[3]) then return {1, tonumber(expiry), 0, -1} end

            local rank = redis.call('ZRANK', KEYS[3], ARGV[2])
            if not rank then return {-2, 0, 0, -1} end                                         -- 대기열에도 없음

            redis.call('ZADD', KEYS[4], 'XX', ARGV[3], ARGV[2])                                -- 대기자 생존 신호
            local admitted, oldest = recentAdmissions(KEYS[5], ARGV[4])
            return {0, rank, admitted, oldest}
            """);

    private static final DefaultRedisScript<Long> VALIDATE_ACTIVE_SCRIPT = script(Long.class, """
            -- KEYS: 1=token 2=active
            -- ARGV: 1=token 2=userId 3=nowMs
            local stored = redis.call('GET', KEYS[1])
            if not stored or stored ~= ARGV[1] then return -1 end
            local expiry = redis.call('ZSCORE', KEYS[2], ARGV[2])
            if not expiry or tonumber(expiry) < tonumber(ARGV[3]) then return -2 end
            return 1
            """);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script(Long.class, """
            -- KEYS: 1=token 2=active 3=wait 4=seen
            -- ARGV: 1=token 2=userId
            local stored = redis.call('GET', KEYS[1])
            if not stored or stored ~= ARGV[1] then return 0 end
            redis.call('ZREM', KEYS[2], ARGV[2])
            redis.call('ZREM', KEYS[3], ARGV[2])
            redis.call('ZREM', KEYS[4], ARGV[2])
            redis.call('DEL', KEYS[1])
            return 1
            """);

    private static final DefaultRedisScript<List> PROMOTE_SCRIPT = script("""
            -- KEYS: 1=wait 2=active 3=seen 4=activeSchedules 5=admissions
            -- ARGV: 1=maxActive 2=batchSize 3=nowMs 4=sessionExpiryMs 5=staleBeforeMs 6=scheduleId
            --       7=tokenKeyPrefix 8=staleScanLimit 9=admissionWindowStartMs 10=admissionMemberPrefix

            -- 1) 세션이 끝난 입장 사용자 정리
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[3])

            -- 2) 조회가 끊긴 대기자 정리
            local stale = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', '(' .. ARGV[5], 'LIMIT', 0, tonumber(ARGV[8]))
            for _, uid in ipairs(stale) do
              redis.call('ZREM', KEYS[1], uid)
              redis.call('ZREM', KEYS[3], uid)
              redis.call('DEL', ARGV[7] .. uid)
            end

            -- 3) 빈 슬롯만큼 승격, 만료 시각은 승격 시점에 고정 (토큰도 같이 만료)
            local promoted = 0
            local available = tonumber(ARGV[1]) - redis.call('ZCARD', KEYS[2])
            if available > 0 then
              local members = redis.call('ZRANGE', KEYS[1], 0, math.min(available, tonumber(ARGV[2])) - 1)
              for _, uid in ipairs(members) do
                redis.call('ZADD', KEYS[2], ARGV[4], uid)
                redis.call('ZREM', KEYS[1], uid)
                redis.call('ZREM', KEYS[3], uid)
                redis.call('PEXPIREAT', ARGV[7] .. uid, ARGV[4])
              end
              promoted = #members
            end

            -- 4) 최근 입장 기록(대기 시간 예측용)
            if promoted > 0 then
              redis.call('ZADD', KEYS[5], ARGV[3], ARGV[10] .. ':' .. promoted)
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', '(' .. ARGV[9])

            -- 5) 대기자도 입장자도 없는 일정은 순회 대상에서 제외
            if redis.call('ZCARD', KEYS[1]) == 0 and redis.call('ZCARD', KEYS[2]) == 0 then
              redis.call('SREM', KEYS[4], ARGV[6])
            end
            return {promoted, #stale}
            """);

    public enum EntryOutcome {
        ENTERED, ALREADY_ACTIVE, ALREADY_WAITING
    }

    public enum StatusOutcome {
        ACTIVE, WAITING, INVALID_TOKEN, NOT_IN_QUEUE
    }

    /** 최근 입장 기록, oldestAt이 음수면 기록 없음 */
    public record RecentAdmissions(
            long count,
            long oldestAt
    ) {
        public boolean isEmpty() {
            return count <= 0 || oldestAt < 0;
        }
    }

    public record EntryResult(
            EntryOutcome outcome,
            long rank, // 0부터 시작하는 대기 순번
            RecentAdmissions admissions
    ) {}

    public record StatusResult(
            StatusOutcome outcome,
            long rank, // WAITING일 때 0부터 시작하는 대기 순번
            long activeExpiresAt, // ACTIVE일 때 세션 만료 시각(ms)
            RecentAdmissions admissions
    ) {}

    public record PromotionResult(
            long promoted,
            long staleRemoved
    ) {}

    public EntryResult enter(long scheduleId,
                             long userId,
                             String token,
                             long nowMs,
                             long tokenTtlMs,
                             long admissionWindowStartMs) {
        List<?> result = redis.execute(
                ENTER_SCRIPT,
                List.of(
                        QueueKeys.waiting(scheduleId),
                        QueueKeys.active(scheduleId),
                        QueueKeys.token(scheduleId, userId),
                        QueueKeys.seen(scheduleId),
                        QueueKeys.sequence(scheduleId),
                        QueueKeys.ACTIVE_SCHEDULES,
                        QueueKeys.admissions(scheduleId)
                ),
                String.valueOf(userId),
                token,
                String.valueOf(nowMs),
                String.valueOf(tokenTtlMs),
                String.valueOf(scheduleId),
                String.valueOf(admissionWindowStartMs)
        );

        long code = number(result, 0);
        EntryOutcome outcome = code == -1 ? EntryOutcome.ALREADY_ACTIVE
                : code == -2 ? EntryOutcome.ALREADY_WAITING
                : EntryOutcome.ENTERED;
        return new EntryResult(outcome, number(result, 1), admissions(result));
    }

    public StatusResult status(long scheduleId,
                               long userId,
                               String token,
                               long nowMs,
                               long admissionWindowStartMs) {
        List<?> result = redis.execute(
                STATUS_SCRIPT,
                List.of(
                        QueueKeys.token(scheduleId, userId),
                        QueueKeys.active(scheduleId),
                        QueueKeys.waiting(scheduleId),
                        QueueKeys.seen(scheduleId),
                        QueueKeys.admissions(scheduleId)
                ),
                token,
                String.valueOf(userId),
                String.valueOf(nowMs),
                String.valueOf(admissionWindowStartMs)
        );

        long code = number(result, 0);
        long value = number(result, 1);
        return switch ((int) code) {
            case 1 -> new StatusResult(StatusOutcome.ACTIVE, 0, value, admissions(result));
            case 0 -> new StatusResult(StatusOutcome.WAITING, value, 0, admissions(result));
            case -1 -> new StatusResult(StatusOutcome.INVALID_TOKEN, 0, 0, admissions(result));
            default -> new StatusResult(StatusOutcome.NOT_IN_QUEUE, 0, 0, admissions(result));
        };
    }

    /** 1: 입장 상태, -1: 토큰 불일치, -2: 입장 상태 아님 */
    public long validateActive(long scheduleId, long userId, String token, long nowMs) {
        Long result = redis.execute(
                VALIDATE_ACTIVE_SCRIPT,
                List.of(QueueKeys.token(scheduleId, userId), QueueKeys.active(scheduleId)),
                token,
                String.valueOf(userId),
                String.valueOf(nowMs)
        );
        return result == null ? -1 : result;
    }

    /** 토큰이 맞을 때만 제거 */
    public boolean release(long scheduleId, long userId, String token) {
        Long result = redis.execute(
                RELEASE_SCRIPT,
                List.of(
                        QueueKeys.token(scheduleId, userId),
                        QueueKeys.active(scheduleId),
                        QueueKeys.waiting(scheduleId),
                        QueueKeys.seen(scheduleId)
                ),
                token,
                String.valueOf(userId)
        );
        return result != null && result == 1L;
    }

    public PromotionResult promote(long scheduleId,
                                   long maxActive,
                                   int batchSize,
                                   long nowMs,
                                   long sessionExpiryMs,
                                   long staleBeforeMs,
                                   long admissionWindowStartMs) {
        List<?> result = redis.execute(
                PROMOTE_SCRIPT,
                List.of(
                        QueueKeys.waiting(scheduleId),
                        QueueKeys.active(scheduleId),
                        QueueKeys.seen(scheduleId),
                        QueueKeys.ACTIVE_SCHEDULES,
                        QueueKeys.admissions(scheduleId)
                ),
                String.valueOf(maxActive),
                String.valueOf(batchSize),
                String.valueOf(nowMs),
                String.valueOf(sessionExpiryMs),
                String.valueOf(staleBeforeMs),
                String.valueOf(scheduleId),
                QueueKeys.tokenPrefix(scheduleId),
                String.valueOf(STALE_SCAN_LIMIT),
                String.valueOf(admissionWindowStartMs),
                nowMs + ":" + UUID.randomUUID().toString().substring(0, 8)
        );
        return new PromotionResult(number(result, 0), number(result, 1));
    }

    /** 이미 다른 인스턴스가 잡았으면 false, 해제 없이 TTL로 만료 */
    public boolean tryAcquirePromotionLock(String owner, Duration ttl) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(QueueKeys.PROMOTION_LOCK, owner, ttl)
        );
    }

    public Set<String> activeScheduleIds() {
        return redis.opsForSet().members(QueueKeys.ACTIVE_SCHEDULES);
    }

    private static RecentAdmissions admissions(List<?> result) {
        return new RecentAdmissions(number(result, 2), number(result, 3));
    }

    private static long number(List<?> result, int index) {
        if (result == null || result.size() <= index || result.get(index) == null) {
            return 0;
        }
        return ((Number) result.get(index)).longValue();
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> script(String text) {
        return script(List.class, text);
    }

    private static <T> DefaultRedisScript<T> script(Class<T> resultType, String text) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setResultType(resultType);
        script.setScriptText(text);
        return script;
    }
}
