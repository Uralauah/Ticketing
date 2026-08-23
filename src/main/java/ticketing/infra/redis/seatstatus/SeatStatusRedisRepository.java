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

    // 스크립트 1회당 적재 좌석 수 (Redis 장시간 점유 방지)
    private static final int INIT_CHUNK_SIZE = 1_000;

    private final StringRedisTemplate redis;

    private static final DefaultRedisScript<Long> RECORD_AND_PUBLISH_SCRIPT;
    private static final DefaultRedisScript<Long> INIT_STATUSES_IF_ABSENT_SCRIPT;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;

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

        // HSETNX → 초기화 중 기록된 최신 변경을 덮어쓰지 않음
        INIT_STATUSES_IF_ABSENT_SCRIPT = new DefaultRedisScript<>();
        INIT_STATUSES_IF_ABSENT_SCRIPT.setResultType(Long.class);
        INIT_STATUSES_IF_ABSENT_SCRIPT.setScriptText("""
            -- KEYS[1] hashKey
            -- ARGV    seatId1, status1, seatId2, status2, ...
            local added = 0
            for i = 1, #ARGV, 2 do
              added = added + redis.call('HSETNX', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            return added
        """);

        // 내가 잡은 락일 때만 삭제
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
        RELEASE_LOCK_SCRIPT.setScriptText("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
        """);
    }

    public boolean isInitDone(long scheduleId) {
        Boolean ok = redis.hasKey(SeatStatusRedisKeys.initDone(scheduleId));
        return Boolean.TRUE.equals(ok);
    }

    public boolean tryInitLock(long scheduleId, String owner, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(
                SeatStatusRedisKeys.initLock(scheduleId),
                owner,
                ttl
        );
        return Boolean.TRUE.equals(ok);
    }

    public void releaseInitLock(long scheduleId, String owner) {
        redis.execute(
                RELEASE_LOCK_SCRIPT,
                List.of(SeatStatusRedisKeys.initLock(scheduleId)),
                owner
        );
    }

    public void markInitDone(long scheduleId) {
        redis.opsForValue().set(SeatStatusRedisKeys.initDone(scheduleId), "1");
    }

    public void initVersionIfAbsent(long scheduleId) {
        redis.opsForValue().setIfAbsent(SeatStatusRedisKeys.ver(scheduleId), "0");
    }

    /** 초기화 여부와 version을 MGET 1회로 조회, 초기화 전이면 null */
    public Long versionIfInitialized(long scheduleId) {
        List<String> values = redis.opsForValue().multiGet(List.of(
                SeatStatusRedisKeys.initDone(scheduleId),
                SeatStatusRedisKeys.ver(scheduleId)
        ));
        if (values == null || values.get(0) == null) {
            return null;
        }
        return values.get(1) == null ? 0L : Long.parseLong(values.get(1));
    }

    public long currentVersion(long scheduleId) {
        String v = redis.opsForValue().get(SeatStatusRedisKeys.ver(scheduleId));
        return v == null ? 0L : Long.parseLong(v);
    }

    /** 비어 있는 좌석 필드만 채움 */
    public void initStatusesIfAbsent(long scheduleId, Map<String, String> seatIdToStatus) {
        if (seatIdToStatus == null || seatIdToStatus.isEmpty()) return;

        List<String> hashKey = List.of(SeatStatusRedisKeys.hash(scheduleId));
        List<String> args = new ArrayList<>(INIT_CHUNK_SIZE * 2);
        for (Map.Entry<String, String> entry : seatIdToStatus.entrySet()) {
            args.add(entry.getKey());
            args.add(entry.getValue());
            if (args.size() == INIT_CHUNK_SIZE * 2) {
                redis.execute(INIT_STATUSES_IF_ABSENT_SCRIPT, hashKey, args.toArray());
                args.clear();
            }
        }
        if (!args.isEmpty()) {
            redis.execute(INIT_STATUSES_IF_ABSENT_SCRIPT, hashKey, args.toArray());
        }
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
