package ticketing.global.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ticketing.support.IntegrationTestSupport;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("부하 테스트 데이터 적재 (MySQL 8)")
class LoadTestDataSeederTest extends IntegrationTestSupport {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("빈 DB에 사용자 10만·일정 3·좌석 1.05만 적재, 이미 있으면 재적재 안 함")
    void 빈_DB에_한_번만_적재() {
        LoadTestDataSeeder seeder = new LoadTestDataSeeder(jdbcTemplate, dataSource);

        seeder.run(null);
        seeder.run(null);

        assertThat(count("users")).isEqualTo(100_000L);
        assertThat(count("concert_schedule")).isEqualTo(3L);
        assertThat(count("seat")).isEqualTo(10_500L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE status = 'AVAILABLE'",
                Long.class
        )).isEqualTo(10_500L);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
