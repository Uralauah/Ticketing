package ticketing.domain.concert.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.infra.redis.seatstatus.SeatStatusRedisKeys;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;
import ticketing.support.RedisTestSupport;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("좌석 상태 기록: Outbox id로 중복·역순 적용 방지 (Redis 7)")
class SeatStatusRecordFencingTest extends RedisTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final long SEAT_ID = 10L;

    private SeatStatusRedisRepository redisRepo;

    @BeforeEach
    void setUp() {
        redisRepo = new SeatStatusRedisRepository(redisTemplate);
        redisRepo.initStatusesIfAbsent(
                SCHEDULE_ID,
                Map.of(String.valueOf(SEAT_ID), "AVAILABLE", "11", "AVAILABLE")
        );
        redisRepo.finishInit(SCHEDULE_ID);
    }

    @Test
    @DisplayName("같은 변경 중복 수신: 두 번째는 version 증가·발행·남은 좌석 감소 없음")
    void 같은_변경_한_번만_적용() {
        long first = record(SeatStatus.RESERVED, 5);
        long second = record(SeatStatus.RESERVED, 5);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(redisRepo.currentVersion(SCHEDULE_ID)).isEqualTo(1);
        assertThat(redisRepo.remainingSeats(SCHEDULE_ID)).isEqualTo(1);
        assertThat(redisTemplate.opsForZSet().size(SeatStatusRedisKeys.chg(SCHEDULE_ID)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("늦게 도착한 과거 변경: 최신 상태 유지 (예약 → 취소 뒤 예약 변경 재수신)")
    void 늦은_과거_변경_무시() {
        record(SeatStatus.RESERVED, 5);
        record(SeatStatus.AVAILABLE, 7);

        assertThat(record(SeatStatus.RESERVED, 5)).isZero();

        assertThat(status()).isEqualTo("AVAILABLE");
        assertThat(redisRepo.remainingSeats(SCHEDULE_ID)).isEqualTo(2);
        assertThat(redisRepo.appliedOutboxIds(SCHEDULE_ID, List.of(SEAT_ID, 11L)))
                .containsEntry(SEAT_ID, 7L)
                .containsEntry(11L, 0L);
    }

    @Test
    @DisplayName("Outbox id 없이(0) 기록: 확인 없이 매번 적용 (실험·테스트용 경로)")
    void outbox_id_없으면_매번_적용() {
        assertThat(redisRepo.recordChangeAndPublishAtomic(
                SCHEDULE_ID,
                SEAT_ID,
                SeatStatus.RESERVED,
                1_000,
                0L
        )).isEqualTo(1);
        assertThat(redisRepo.recordChangeAndPublishAtomic(
                SCHEDULE_ID,
                SEAT_ID,
                SeatStatus.RESERVED,
                1_000,
                0L
        )).isEqualTo(2);
    }

    private long record(SeatStatus status, long outboxId) {
        return redisRepo.recordChangeAndPublishAtomic(
                SCHEDULE_ID,
                SEAT_ID,
                status,
                1_000,
                System.currentTimeMillis(),
                outboxId
        );
    }

    private Object status() {
        return redisTemplate.opsForHash().get(
                SeatStatusRedisKeys.hash(SCHEDULE_ID),
                String.valueOf(SEAT_ID)
        );
    }
}
