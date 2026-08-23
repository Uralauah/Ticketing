package ticketing.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 스프링 컨텍스트 없이 실제 Redis로 Lua·저장소 검증 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class RedisTestSupport {

    protected static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connectRedis() {
        if (redisTemplate != null) {
            return;
        }
        GenericContainer<?> redis = TestContainers.redis();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                redis.getHost(),
                redis.getMappedPort(6379)
        );
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @BeforeEach
    protected void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}
