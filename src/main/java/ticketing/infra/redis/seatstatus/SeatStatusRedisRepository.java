package ticketing.infra.redis.seatstatus;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import ticketing.domain.concert.entity.SeatStatus;

import java.time.Duration;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class SeatStatusRedisRepository {

    private final StringRedisTemplate redis;

    private static final DefaultRedisScript<Long> RECORD_AND_PUBLISH_SCRIPT;

    static {
        RECORD_AND_PUBLISH_SCRIPT = new DefaultRedisScript<>();
        RECORD_AND_PUBLISH_SCRIPT.setResultType(Long.class);
        RECORD_AND_PUBLISH_SCRIPT.setScriptText("""
            -- KEYS[1] verKey
            -- KEYS[2] hashKey
            -- KEYS[3] chgKey
            -- ARGV[1] seatId
            -- ARGV[2] status
            -- ARGV[3] window
            -- ARGV[4] channel
            
            local v = redis.call('INCR', KEYS[1])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
            
            local member = tostring(v) .. '|' .. ARGV[1] .. '|' .. ARGV[2]
            redis.call('ZADD', KEYS[3], v, member)
            
            local window = tonumber(ARGV[3])
            local min_keep = v - window
            if min_keep >= 0 then
              redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, min_keep)
            end
            
            redis.call('PUBLISH', ARGV[4], member)
            return v
        """);
    }

    public boolean isInitDone(long scheduleId) {
        Boolean ok = redis.hasKey(SeatStatusRedisKeys.initDone(scheduleId));
        return Boolean.TRUE.equals(ok);
    }

    public boolean tryInitLock(long scheduleId, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(SeatStatusRedisKeys.initLock(scheduleId), "1", ttl);
        return Boolean.TRUE.equals(ok);
    }

    public void markInitDone(long scheduleId) {
        redis.opsForValue().set(SeatStatusRedisKeys.initDone(scheduleId), "1");
    }

    public void initVersionIfAbsent(long scheduleId) {
        redis.opsForValue().setIfAbsent(SeatStatusRedisKeys.ver(scheduleId), "0");
    }

    public long currentVersion(long scheduleId) {
        String v = redis.opsForValue().get(SeatStatusRedisKeys.ver(scheduleId));
        return v == null ? 0L : Long.parseLong(v);
    }

    public void putAllStatuses(long scheduleId, Map<String, String> seatIdToStatus) {
        if (seatIdToStatus == null || seatIdToStatus.isEmpty()) return;
        redis.opsForHash().putAll(SeatStatusRedisKeys.hash(scheduleId), seatIdToStatus);
    }

    public Map<Object, Object> getAllStatuses(long scheduleId) {
        return redis.opsForHash().entries(SeatStatusRedisKeys.hash(scheduleId));
    }

    public Set<String> rangeChanges(long scheduleId, long fromVersionInclusive, long toVersionInclusive) {
        return redis.opsForZSet().rangeByScore(
                SeatStatusRedisKeys.chg(scheduleId),
                fromVersionInclusive,
                toVersionInclusive
        );
    }

    public long recordChangeAndPublishAtomic(long scheduleId, long seatId, SeatStatus status, long window) {
        String channel = SeatStatusTopic.channel(scheduleId);

        Long v = redis.execute(
                RECORD_AND_PUBLISH_SCRIPT,
                List.of(
                        SeatStatusRedisKeys.ver(scheduleId),
                        SeatStatusRedisKeys.hash(scheduleId),
                        SeatStatusRedisKeys.chg(scheduleId)
                ),
                String.valueOf(seatId),
                status.name(),
                String.valueOf(window),
                channel
        );

        return v == null ? 0L : v;
    }
}