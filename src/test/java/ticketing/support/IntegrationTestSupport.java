package ticketing.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 MySQL 8, Redis 7 기반 통합 테스트 공통 부모 (Docker가 없으면 건너뜀)
 * H2는 InnoDB 락 동작이 달라 동시성 검증에 부적합
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestSupport {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        // JVM 단위로 한 번만 띄움 (클래스마다 띄우면 캐시된 컨텍스트가 옛 포트를 바라봄)
        MYSQL.start();
        REDIS.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUpStores() {
        // TRUNCATE는 ID를 재사용해 일정 ID 기반 로컬 캐시가 꼬이므로 DELETE 사용
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("DELETE FROM seat");
        jdbcTemplate.update("DELETE FROM concert_schedule");
        jdbcTemplate.update("DELETE FROM concert");
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}
