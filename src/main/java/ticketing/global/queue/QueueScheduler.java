package ticketing.global.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static ticketing.global.queue.QueueProperties.ACTIVE_EVENTS_KEY;
import static ticketing.global.queue.QueueProperties.ACTIVE_QUEUE_KEY;
import static ticketing.global.queue.QueueProperties.MAX_ACTIVE_USERS;
import static ticketing.global.queue.QueueProperties.WAIT_QUEUE_KEY;

@Component
@RequiredArgsConstructor
public class QueueScheduler {
    private final StringRedisTemplate redisTemplate;
    private static final int BATCH_SIZE = 200;

    private static final String PROMOTE_SCRIPT = """
      local waitKey   = KEYS[1]
      local activeKey = KEYS[2]
      local eventsKey = KEYS[3]
      local maxActive = tonumber(ARGV[1])
      local batchSize = tonumber(ARGV[2])
      local expiry    = tonumber(ARGV[3])
      local now       = tonumber(ARGV[4])
      local eventId   = ARGV[5]

      redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', now - 1)
      local activeCount = redis.call('ZCARD', activeKey)
      local available   = maxActive - activeCount
      if available <= 0 then return 0 end

      local toPromote = math.min(available, batchSize)
      local members   = redis.call('ZRANGE', waitKey, 0, toPromote - 1)

      for _, uid in ipairs(members) do
          redis.call('ZADD', activeKey, expiry, uid)
          redis.call('ZREM', waitKey, uid)
      end

      if redis.call('ZCARD', waitKey) == 0 and redis.call('ZCARD', activeKey) == 0 then
          redis.call('SREM', eventsKey, eventId)
      end

      return #members
      """;


    @Scheduled(fixedRate = 10_000)
    public void promoteWaitingUsers(){
        Set<String> activeEvents = redisTemplate.opsForSet()
                .members(ACTIVE_EVENTS_KEY);

        if(activeEvents==null)
            return;

        for (String eventId : activeEvents) {
            promoteForEvent(eventId);
        }
    }

    private void promoteForEvent(String eventId) {
        String waitKey = String.format(WAIT_QUEUE_KEY, eventId);
        String activeKey = String.format(ACTIVE_QUEUE_KEY, eventId);
        long now = System.currentTimeMillis();
        long expiry = now + Duration.ofMinutes(5).toMillis();

        redisTemplate.execute(
                new DefaultRedisScript<>(PROMOTE_SCRIPT, Long.class),
                List.of(waitKey, activeKey, ACTIVE_EVENTS_KEY),
                String.valueOf(MAX_ACTIVE_USERS),
                String.valueOf(BATCH_SIZE),
                String.valueOf(expiry),
                String.valueOf(now),
                eventId
        );
    }
}
