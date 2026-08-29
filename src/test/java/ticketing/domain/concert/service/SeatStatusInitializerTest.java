package ticketing.domain.concert.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;
import ticketing.infra.redis.seatstatus.SeatStatusRedisRepository;
import ticketing.support.ConcurrentRunner;
import ticketing.support.RedisTestSupport;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.LongStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("좌석 상태 초기화 (Redis 7)")
class SeatStatusInitializerTest extends RedisTestSupport {

    private static final long SCHEDULE_ID = 1L;

    @Mock private SeatRepository seatRepository;
    @Mock private ConcertScheduleRepository concertScheduleRepository;

    private SeatStatusRedisRepository redisRepo;
    private SeatStatusInitializer initializer;

    @BeforeEach
    void setUp() {
        redisRepo = new SeatStatusRedisRepository(redisTemplate);
        initializer = new SeatStatusInitializer(
                seatRepository,
                concertScheduleRepository,
                redisRepo
        );
        lenient().when(concertScheduleRepository.existsById(SCHEDULE_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("DB 조회 중 기록된 최신 변경을 DB의 과거 값으로 덮어쓰지 않음")
    void 적재_중_최신_변경_보존() throws Exception {
        CountDownLatch queried = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(seatRepository.findSeatStatusByScheduleId(SCHEDULE_ID)).thenAnswer(invocation -> {
            queried.countDown();
            release.await(5, SECONDS);
            // 좌석 77이 아직 AVAILABLE이던 시점에 읽은 결과
            return List.of(
                    new SeatStatusRes(77L, SeatStatus.AVAILABLE),
                    new SeatStatusRes(78L, SeatStatus.AVAILABLE)
            );
        });

        CompletableFuture<Void> init = CompletableFuture.runAsync(
                () -> initializer.ensureInitialized(SCHEDULE_ID)
        );
        assertThat(queried.await(5, SECONDS)).isTrue();

        // 초기화가 DB 결과를 쓰기 전에 좌석 77 예약이 커밋되어 Redis에 기록
        redisRepo.recordChangeAndPublishAtomic(
                SCHEDULE_ID,
                77L,
                SeatStatus.RESERVED,
                1_000,
                System.currentTimeMillis()
        );
        release.countDown();
        init.get(5, SECONDS);

        assertThat(redisRepo.getAllStatuses(SCHEDULE_ID))
                .containsEntry("77", SeatStatus.RESERVED.name())
                .containsEntry("78", SeatStatus.AVAILABLE.name());
        assertThat(redisRepo.currentVersion(SCHEDULE_ID)).isEqualTo(1L);
        assertThat(redisRepo.isInitDone(SCHEDULE_ID)).isTrue();
    }

    @Test
    @DisplayName("동시 20개 요청: DB 적재는 한 번만, 나머지는 완료 대기")
    void 동시_요청_적재_한_번만() throws Exception {
        when(seatRepository.findSeatStatusByScheduleId(SCHEDULE_ID)).thenAnswer(invocation -> {
            Thread.sleep(300);
            return List.of(new SeatStatusRes(1L, SeatStatus.AVAILABLE));
        });

        ConcurrentRunner.Result result = ConcurrentRunner.run(
                20,
                i -> initializer.ensureInitialized(SCHEDULE_ID)
        );

        assertThat(result.failures()).isEmpty();
        verify(seatRepository, times(1)).findSeatStatusByScheduleId(SCHEDULE_ID);
    }

    @Test
    @DisplayName("다른 곳의 초기화가 제한 시간 내 미완료: 직접 적재 없이 503 반환")
    void 락_없는_요청_적재_포기() {
        redisRepo.tryInitLock(SCHEDULE_ID, "other-instance", Duration.ofSeconds(10));

        assertThatThrownBy(() -> initializer.ensureInitialized(SCHEDULE_ID))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getExceptionCode())
                                .isEqualTo(SeatExceptionCode.SEAT_STATUS_NOT_READY));
        verifyNoInteractions(seatRepository);
    }

    @Test
    @DisplayName("좌석이 청크 크기보다 많아도 전부 적재")
    void 청크_초과_좌석_전부_적재() {
        List<SeatStatusRes> seats = LongStream.rangeClosed(1, 2_500)
                .mapToObj(id -> new SeatStatusRes(id, SeatStatus.AVAILABLE))
                .toList();
        when(seatRepository.findSeatStatusByScheduleId(SCHEDULE_ID)).thenReturn(seats);

        initializer.ensureInitialized(SCHEDULE_ID);

        assertThat(redisRepo.getAllStatuses(SCHEDULE_ID)).hasSize(2_500);
        assertThat(redisRepo.currentVersion(SCHEDULE_ID)).isZero();
    }

    @Test
    @DisplayName("없는 일정: SCHEDULE_NOT_FOUND 예외, 락 해제로 다음 요청 재시도 가능")
    void 없는_일정_락_해제() {
        when(concertScheduleRepository.existsById(SCHEDULE_ID)).thenReturn(false);

        assertThatThrownBy(() -> initializer.ensureInitialized(SCHEDULE_ID))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getExceptionCode())
                                .isEqualTo(ScheduleExceptionCode.SCHEDULE_NOT_FOUND));
        assertThat(redisRepo.tryInitLock(SCHEDULE_ID, "next", Duration.ofSeconds(10))).isTrue();
    }

    @Test
    @DisplayName("초기화 락은 잡은 쪽만 해제 가능")
    void 락_소유자만_해제() {
        redisRepo.tryInitLock(SCHEDULE_ID, "owner-a", Duration.ofSeconds(10));

        redisRepo.releaseInitLock(SCHEDULE_ID, "owner-b");
        assertThat(redisRepo.tryInitLock(SCHEDULE_ID, "owner-b", Duration.ofSeconds(10))).isFalse();

        redisRepo.releaseInitLock(SCHEDULE_ID, "owner-a");
        assertThat(redisRepo.tryInitLock(SCHEDULE_ID, "owner-b", Duration.ofSeconds(10))).isTrue();
    }
}
