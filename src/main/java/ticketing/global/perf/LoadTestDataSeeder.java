package ticketing.global.perf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * 부하 테스트용 더미 데이터 적재 (users가 비어 있을 때만)
 * 좌석 상태 워밍업(SeatStatusWarmUpRunner)보다 먼저 실행 → Order(0)
 */
@Slf4j
@Component
@Order(0)
@ConditionalOnProperty(name = "ticketing.seed.load-test-data", havingValue = "true")
@RequiredArgsConstructor
public class LoadTestDataSeeder implements ApplicationRunner {
    static final String SEED_SCRIPT = "db/seed/load-test-data.sql";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        Long users = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (users != null && users > 0) {
            log.info("부하 테스트 데이터가 이미 있어 적재를 건너뜁니다 (users={})", users);
            return;
        }

        long startedAt = System.nanoTime();
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(SEED_SCRIPT)
        );
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(dataSource);
        log.info("부하 테스트 데이터 적재 완료: {}ms", (System.nanoTime() - startedAt) / 1_000_000);
    }
}
