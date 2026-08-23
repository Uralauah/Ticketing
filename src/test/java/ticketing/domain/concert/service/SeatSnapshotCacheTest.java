package ticketing.domain.concert.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.infra.redis.seatstatus.SeatStatusChangeCodec;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;
import ticketing.support.ConcurrentRunner;
import ticketing.support.RedisTestSupport;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("version 단위 스냅샷 캐시 (Redis 7)")
class SeatSnapshotCacheTest extends RedisTestSupport {

    private static final long SCHEDULE_ID = 1L;

    @Mock private SeatRepository seatRepository;
    @Mock private ConcertScheduleRepository concertScheduleRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SeatStatusRedisRepository redisRepo;
    private SeatSnapshotCache cache;

    @BeforeEach
    void setUp() {
        redisRepo = new SeatStatusRedisRepository(redisTemplate);
        SeatStatusInitializer initializer = new SeatStatusInitializer(seatRepository, concertScheduleRepository, redisRepo);
        SeatStatusLiveService liveService = new SeatStatusLiveService(initializer, redisRepo, new SeatStatusChangeCodec());
        cache = new SeatSnapshotCache(liveService, initializer, redisRepo, objectMapper);

        when(concertScheduleRepository.existsById(SCHEDULE_ID)).thenReturn(true);
        when(seatRepository.findSeatStatusByScheduleId(SCHEDULE_ID)).thenReturn(List.of(
                new SeatStatusRes(1L, SeatStatus.AVAILABLE),
                new SeatStatusRes(2L, SeatStatus.AVAILABLE)
        ));
    }

    @Test
    @DisplayName("같은 version 동시 요청 50건: HGETALL 1회, 같은 바이트 공유")
    void version당_한_번만_생성() throws Exception {
        cache.get(SCHEDULE_ID); // 초기화
        resetCommandStats();

        Set<SeatSnapshotCache.Snapshot> distinct = ConcurrentHashMap.newKeySet();
        ConcurrentRunner.Result result = ConcurrentRunner.run(
                50,
                i -> distinct.add(cache.get(SCHEDULE_ID))
        );

        assertThat(result.failures()).isEmpty();
        assertThat(distinct).hasSize(1);
        assertThat(commandCalls("hgetall")).isZero();
    }

    @Test
    @DisplayName("version 변경 시 한 번 재생성: 새 상태·새 ETag 반환")
    void version_변경시_재생성() throws Exception {
        SeatSnapshotCache.Snapshot before = cache.get(SCHEDULE_ID);
        redisRepo.recordChangeAndPublishAtomic(SCHEDULE_ID, 2L, SeatStatus.RESERVED, 1_000);
        resetCommandStats();

        SeatSnapshotCache.Snapshot after = cache.get(SCHEDULE_ID);
        cache.get(SCHEDULE_ID);

        assertThat(commandCalls("hgetall")).isEqualTo(1);
        assertThat(before.eTag()).isEqualTo("W/\"0\"");
        assertThat(after.eTag()).isEqualTo("W/\"1\"");
        JsonNode body = objectMapper.readTree(after.json());
        assertThat(body.get("currentVersion").asLong()).isEqualTo(1L);
        assertThat(body.get("seats").get(1).get("status").asText()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("Redis가 비워져 version이 되돌아가도 옛 스냅샷을 돌려주지 않는다")
    void rebuildsWhenVersionGoesBackwards() {
        redisRepo.recordChangeAndPublishAtomic(SCHEDULE_ID, 2L, SeatStatus.RESERVED, 1_000);
        assertThat(cache.get(SCHEDULE_ID).version()).isEqualTo(1L);

        flushRedis();

        assertThat(cache.get(SCHEDULE_ID).version()).isZero();
    }

    @Test
    @DisplayName("gzip 본과 원문 JSON 내용 일치")
    void gzip_본_원문_일치() throws Exception {
        SeatSnapshotCache.Snapshot snapshot = cache.get(SCHEDULE_ID);

        try (GZIPInputStream in = new GZIPInputStream(
                new ByteArrayInputStream(snapshot.gzipped())
        )) {
            assertThat(in.readAllBytes()).isEqualTo(snapshot.json());
        }
    }

    private void resetCommandStats() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().resetConfigStats();
            return null;
        });
    }

    private long commandCalls(String command) {
        Properties stats = redisTemplate.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("commandstats"));
        String line = stats == null ? null : stats.getProperty("cmdstat_" + command);
        if (line == null) {
            return 0;
        }
        for (String part : line.split(",")) {
            if (part.startsWith("calls=")) {
                return Long.parseLong(part.substring("calls=".length()));
            }
        }
        return 0;
    }
}
