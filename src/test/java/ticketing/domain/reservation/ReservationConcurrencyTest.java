package ticketing.domain.reservation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ticketing.domain.concert.entity.Concert;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.repository.ConcertRepository;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.reservation.entity.Reservation;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.reservation.service.ReservationService;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예약 동시성(Race Condition) 테스트
 *
 * 목적:
 *   동일 좌석에 N명이 동시에 예약 요청할 때 현재 코드의 취약점을 재현하고,
 *   Pessimistic Lock 적용 후 올바르게 동작하는지 검증한다.
 *
 * 테스트 환경:
 *   - DB  : H2 인메모리 (MySQL 호환 모드) — 외부 MySQL 불필요
 *   - Redis: @MockBean 으로 완전 대체     — 외부 Redis 불필요
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("예약 동시성 테스트")
class ReservationConcurrencyTest {

    // ── 테스트 대상 ──────────────────────────────────────────────────────────────
    @Autowired private ReservationService   reservationService;   // 현재 코드 (락 없음)
    @Autowired private LockedReservation    lockedReservation;    // 수정 후 코드 (Pessimistic Lock)

    // ── 데이터 세팅용 Repository ─────────────────────────────────────────────────
    @Autowired private ReservationRepository      reservationRepository;
    @Autowired private SeatRepository             seatRepository;
    @Autowired private ConcertRepository          concertRepository;
    @Autowired private ConcertScheduleRepository  concertScheduleRepository;
    @Autowired private UserRepository             userRepository;

    // ── Redis 완전 목킹 (테스트에서 Redis 불필요) ─────────────────────────────────
    @MockBean private StringRedisTemplate  stringRedisTemplate;   // SeatStatusRedisRepository 의존
    @MockBean private CacheManager         cacheManager;          // RedisCacheConfig 의존
    @MockBean private SeatStatusLiveService seatStatusLiveService; // afterCommit 의 Redis 호출 차단

    // ── 테스트 데이터 ────────────────────────────────────────────────────────────
    private Long scheduleId;
    private Long seatId;
    private final List<Long> userIds = new ArrayList<>();

    private static final int THREAD_COUNT = 10;   // 동시 요청 수

    // ════════════════════════════════════════════════════════════════════════════
    // 테스트 데이터 초기화 / 정리
    // ════════════════════════════════════════════════════════════════════════════

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        seatRepository.deleteAll();
        concertScheduleRepository.deleteAll();
        concertRepository.deleteAll();
        userRepository.deleteAll();
        userIds.clear();

        // 공연 및 일정
        Concert concert = concertRepository.save(
                Concert.builder().title("동시성 테스트 공연").build());

        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(LocalDateTime.now().plusDays(30))
                        .build());
        scheduleId = schedule.getId();

        // 좌석 1개 — 이 좌석 1자리를 두고 N명이 동시에 경쟁
        Seat seat = seatRepository.save(
                Seat.builder()
                        .concertSchedule(schedule)
                        .seatNum("A-1")
                        .price(50_000)
                        .build());
        seatId = seat.getId();

        // 사용자 10명
        for (int i = 1; i <= THREAD_COUNT; i++) {
            userIds.add(userRepository.save(new User("User_" + i, 1_000_000)).getId());
        }
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        seatRepository.deleteAll();
        concertScheduleRepository.deleteAll();
        concertRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Test 1 : 현재 코드 — 락 없음 → 더블 부킹 발생 (이 테스트는 FAIL 이 정상)
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("동시 10명 예약")
    void concurrent_10_reserve() throws InterruptedException {

        Result result = runConcurrent(
                userId -> reservationService.reserve(userId, scheduleId, seatId)
        );

        /*
         * ⚠️  이 assertion 은 버그가 있을 때 FAIL 한다.
         *     - 정상이면 : dbCount == 1  → 통과
         *     - 버그 있으면: dbCount > 1  → 실패 (더블 부킹 증명)
         *
         * 테스트가 RED 로 나오면 레이스 컨디션이 재현된 것이다.
         */
        assertThat(result.dbCount)
                .as("좌석 1개 예약은 1건이어야 합니다. 실제 %d건 → 더블 부킹!", result.dbCount)
                .isEqualTo(1L);

        assertThat(result.success.get())
                .as("성공은 1건이어야 합니다. 실제 %d건", result.success.get())
                .isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Test 2 : 수정 후 코드 — Pessimistic Lock → 단 1건만 성공 (이 테스트는 PASS 가 정상)
    // ════════════════════════════════════════════════════════════════════════════

//    @Test
//    @DisplayName("[수정 후 코드] Pessimistic Lock 적용 → 딱 1건만 예약, 나머지 실패")
//    void 수정후코드_비관적락_더블부킹_방지() throws InterruptedException {
//
//        Result result = runConcurrent(
//                userId -> lockedReservation.reserve(userId, scheduleId, seatId)
//        );
//
//        result.print("수정 후 코드 (Pessimistic Lock)");
//
//        // DB 예약은 정확히 1건
//        assertThat(result.dbCount)
//                .as("Pessimistic Lock 적용 시 예약은 1건이어야 합니다.")
//                .isEqualTo(1L);
//
//        // 성공 1건, 나머지 9건은 예외(SEAT_NOT_AVAILABLE)로 정상 실패
//        assertThat(result.success.get()).isEqualTo(1);
//        assertThat(result.fail.get()).isEqualTo(THREAD_COUNT - 1);
//
//        // 좌석 상태가 RESERVED 로 변경됐는지 확인
//        Seat finalSeat = seatRepository.findById(seatId).orElseThrow();
//        assertThat(finalSeat.getStatus()).isEqualTo(SeatStatus.RESERVED);
//    }

    // ════════════════════════════════════════════════════════════════════════════
    // 동시 실행 헬퍼
    // ════════════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    interface ReservationAction {
        void execute(Long userId) throws Exception;
    }

    /**
     * N개의 스레드를 만들어 startLatch 로 동시에 출발시킨다.
     * CountDownLatch 구조:
     *   readyLatch  — 모든 스레드가 준비됐을 때 메인 스레드가 감지
     *   startLatch  — 메인 스레드가 countDown() 하면 모든 스레드 동시 출발
     *   doneLatch   — 모든 스레드 완료 후 메인 스레드 재개
     */
    private Result runConcurrent(ReservationAction action) throws InterruptedException {
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger fail    = new AtomicInteger(0);
        List<String>  errors  = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                readyLatch.countDown();          // "나 준비됐어" 신호
                try {
                    startLatch.await();          // 모두 준비될 때까지 대기 후 동시 출발
                    action.execute(userId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    errors.add(String.format("[%s] %s: %s",
                            Thread.currentThread().getName(),
                            e.getClass().getSimpleName(),
                            e.getMessage()));
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();                      // 모든 스레드 준비 완료 대기
        startLatch.countDown();                  // ← 동시 출발!
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (!finished) {
            throw new IllegalStateException("테스트 타임아웃: 30초 내 완료 안 됨");
        }

        long dbCount = reservationRepository.count();
        SeatStatus finalStatus = seatRepository.findById(seatId).orElseThrow().getStatus();

        return new Result(success, fail, errors, dbCount, finalStatus);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 결과 출력 레코드
    // ════════════════════════════════════════════════════════════════════════════

    record Result(
            AtomicInteger success,
            AtomicInteger fail,
            List<String>  errors,
            long          dbCount,
            SeatStatus    seatStatus
    ) {
        void print(String label) {
            System.out.printf("""
 
                    ┌─────────────────────────────────────────────────┐
                    │  %-47s│
                    ├─────────────────────────────────────────────────┤
                    │  동시 스레드 수  : %-28d│
                    │  예약 성공      : %-28d│
                    │  예약 실패      : %-28d│
                    │  DB 실제 예약   : %-22d  %s │
                    │  최종 좌석 상태 : %-28s│
                    └─────────────────────────────────────────────────┘
                    """,
                    label,
                    THREAD_COUNT,
                    success.get(),
                    fail.get(),
                    dbCount, dbCount > 1 ? "⚠  더블부킹!" : "✓ 정상    ",
                    seatStatus
            );
            if (!errors.isEmpty()) {
                System.out.println("  발생 예외 목록:");
                errors.forEach(e -> System.out.println("    " + e));
                System.out.println();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 테스트 전용 Bean: Pessimistic Lock 적용 예약 서비스
    //
    // 현재 ReservationService 는 락이 없으므로, 여기서는 EntityManager 를 직접 사용하여
    // PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) 락을 재현한다.
    //
    // 실제 수정 방법은 SeatRepository 에 아래 메서드를 추가하면 된다:
    //   @Lock(LockModeType.PESSIMISTIC_WRITE)
    //   @Query("SELECT s FROM Seat s WHERE s.id = :id")
    //   Optional<Seat> findByIdWithLock(@Param("id") Long id);
    // ════════════════════════════════════════════════════════════════════════════

    @TestConfiguration
    static class TestConfig {
        @Bean
        LockedReservation lockedReservation(
                UserRepository userRepository,
                ReservationRepository reservationRepository) {
            return new LockedReservation(userRepository, reservationRepository);
        }
    }

    static class LockedReservation {

        private final UserRepository        userRepository;
        private final ReservationRepository reservationRepository;

        @PersistenceContext
        private EntityManager em;

        LockedReservation(UserRepository ur, ReservationRepository rr) {
            this.userRepository        = ur;
            this.reservationRepository = rr;
        }

        /**
         * Pessimistic Lock 적용 예약
         *
         * em.find(..., PESSIMISTIC_WRITE) = SELECT ... FOR UPDATE
         * → 락을 먼저 잡은 트랜잭션이 커밋/롤백하기 전까지
         *   다른 트랜잭션은 같은 행에 접근 불가 (대기)
         * → 락 해제 후 들어온 트랜잭션은 이미 RESERVED 상태를 보고 예외 발생
         */
        @Transactional
        public void reserve(Long userId, Long scheduleId, Long seatId) {
            User user = userRepository.findById(userId).orElseThrow();

            // ← 핵심: FOR UPDATE 로 행 락 획득
            Seat seat = em.find(Seat.class, seatId, LockModeType.PESSIMISTIC_WRITE);
            if (seat == null) throw new IllegalArgumentException("존재하지 않는 좌석: " + seatId);

            // 이미 RESERVED 이면 CustomException(SEAT_NOT_AVAILABLE) 발생
            seat.reserve(scheduleId);
            user.reserve(seat.getPrice());

            reservationRepository.save(Reservation.of(user, seat));
        }
    }
}