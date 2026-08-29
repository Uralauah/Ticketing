package ticketing.domain.concert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ticketing.domain.concert.dto.SeatStatusSnapshotRes;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;

/**
 * 좌석 상태 스냅샷을 version 단위로 한 번만 직렬화해 보관
 * 재생성 중에는 직전 스냅샷 반환 (이후 변경은 클라이언트가 replay로 적용)
 */
@Component
public class SeatSnapshotCache {

    public record Snapshot(long version, byte[] json, byte[] gzipped) {
        /** 같은 version이면 내용이 같고 gzip 여부만 다름 → 약한 ETag */
        public String eTag() {
            return "W/\"" + version + "\"";
        }
    }

    private final SeatStatusLiveService liveService;
    private final SeatStatusInitializer initializer;
    private final SeatStatusRedisRepository redisRepo;
    private final ObjectMapper objectMapper;
    private final Timer buildTimer;

    private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> rebuildLocks = new ConcurrentHashMap<>();

    public SeatSnapshotCache(SeatStatusLiveService liveService,
                             SeatStatusInitializer initializer,
                             SeatStatusRedisRepository redisRepo,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.liveService = liveService;
        this.initializer = initializer;
        this.redisRepo = redisRepo;
        this.objectMapper = objectMapper;
        this.buildTimer = Timer.builder("ticketing.seat.snapshot.build")
                .description("좌석 상태 스냅샷 재생성(HGETALL + 직렬화 + 압축)")
                .register(meterRegistry);
    }

    public Snapshot get(long scheduleId) {
        long current = currentVersion(scheduleId);
        Snapshot cached = snapshots.get(scheduleId);
        // Redis 초기화로 version이 되돌아간 경우도 재생성되도록 == 비교
        if (cached != null && cached.version() == current) {
            return cached;
        }

        ReentrantLock lock = rebuildLocks.computeIfAbsent(scheduleId, k -> new ReentrantLock());
        if (cached != null) {
            if (!lock.tryLock()) {
                return cached; // 재생성 중이면 직전 스냅샷
            }
        } else {
            lock.lock(); // 최초 생성은 끝날 때까지 대기
        }
        try {
            Snapshot latest = snapshots.get(scheduleId);
            if (latest != null && latest.version() == current) {
                return latest;
            }

            Snapshot built = buildTimer.record(() -> build(scheduleId));
            snapshots.put(scheduleId, built);
            return built;
        } finally {
            lock.unlock();
        }
    }

    private long currentVersion(long scheduleId) {
        Long version = redisRepo.versionIfInitialized(scheduleId);
        if (version != null) {
            return version;
        }
        initializer.ensureInitialized(scheduleId);
        return redisRepo.currentVersion(scheduleId);
    }

    private Snapshot build(long scheduleId) {
        SeatStatusSnapshotRes snapshot = liveService.snapshot(scheduleId);
        try {
            byte[] json = objectMapper.writeValueAsBytes(snapshot);
            return new Snapshot(snapshot.currentVersion(), json, gzip(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("스냅샷 직렬화 실패: scheduleId=" + scheduleId, e);
        }
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 8);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
