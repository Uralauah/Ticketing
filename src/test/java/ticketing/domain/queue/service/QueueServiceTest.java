package ticketing.domain.queue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import ticketing.domain.queue.dto.QueueEntryResponse;
import ticketing.domain.queue.dto.QueueStatusResponse;
import ticketing.domain.queue.exception.QueueExceptionCode;
import ticketing.global.exception.CustomException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static ticketing.global.queue.QueueProperties.ACTIVE_EVENTS_KEY;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueService 단위 테스트")
@SuppressWarnings("unchecked")
class QueueServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock SetOperations<String, String> setOps;
    @InjectMocks QueueService queueService;

    private static final Long   EVENT_ID    = 1L;
    private static final Long   USER_ID     = 100L;
    private static final String USER_ID_STR = "100";
    private static final String WAIT_KEY    = "queue:wait:1";
    private static final String ACTIVE_KEY  = "queue:active:1";
    private static final String TOKEN_KEY   = "queue:token:1:100";
    private static final String TOKEN       = "test-token";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // enterQueue
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enterQueue")
    class EnterQueue {

        @Test
        @DisplayName("정상 진입: 토큰·순위·예상 대기시간 반환")
        void 정상_진입() {
            when_setIfAbsent_returns(true);
            when(zSetOps.rank(WAIT_KEY, USER_ID_STR)).thenReturn(4L);

            QueueEntryResponse res = queueService.enterQueue(EVENT_ID, USER_ID);

            assertThat(res.token()).isNotBlank();
            assertThat(res.rank()).isEqualTo(5L);
            assertThat(res.estimateWaitTime()).isEqualTo(0L);  // (4/200)*10 = 0
            verify(zSetOps).addIfAbsent(eq(WAIT_KEY), eq(USER_ID_STR), anyDouble());
            verify(setOps).add(ACTIVE_EVENTS_KEY, String.valueOf(EVENT_ID));
        }

        @Test
        @DisplayName("순위 200 이상: 대기 시간 10초 단위로 증가")
        void 대기시간_계산() {
            when_setIfAbsent_returns(true);
            when(zSetOps.rank(WAIT_KEY, USER_ID_STR)).thenReturn(200L);

            QueueEntryResponse res = queueService.enterQueue(EVENT_ID, USER_ID);

            assertThat(res.rank()).isEqualTo(201L);
            assertThat(res.estimateWaitTime()).isEqualTo(10L);  // (200/200)*10
        }

        @Test
        @DisplayName("이미 진입한 사용자: ALREADY_IN_QUEUE 예외")
        void 중복_진입_예외() {
            when_setIfAbsent_returns(false);

            assertThatThrownBy(() -> queueService.enterQueue(EVENT_ID, USER_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getExceptionCode())
                    .isEqualTo(QueueExceptionCode.ALREADY_IN_QUEUE);

            verifyNoInteractions(zSetOps);
        }

        @Test
        @DisplayName("rank 조회 실패: NOT_IN_QUEUE 예외 + 토큰 롤백")
        void rank_실패시_토큰_롤백() {
            when_setIfAbsent_returns(true);
            when(zSetOps.rank(WAIT_KEY, USER_ID_STR)).thenReturn(null);

            assertThatThrownBy(() -> queueService.enterQueue(EVENT_ID, USER_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getExceptionCode())
                    .isEqualTo(QueueExceptionCode.NOT_IN_QUEUE);

            verify(redisTemplate).delete(TOKEN_KEY);
        }

        @Test
        @DisplayName("동시 20개 요청: setIfAbsent 원자성으로 정확히 1건만 성공")
        void 동시_요청_하나만_성공() throws InterruptedException {
            int threadCount = 20;
            AtomicBoolean acquired = new AtomicBoolean(false);

            // AtomicBoolean.compareAndSet으로 Redis SET NX의 원자적 동작을 재현
            when(valueOps.setIfAbsent(eq(TOKEN_KEY), anyString(), eq(Duration.ofHours(24))))
                    .thenAnswer(inv -> acquired.compareAndSet(false, true));
            lenient().when(zSetOps.rank(WAIT_KEY, USER_ID_STR)).thenReturn(0L);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch  = new CountDownLatch(threadCount);
            AtomicInteger successCnt  = new AtomicInteger(0);
            AtomicInteger failCnt     = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        queueService.enterQueue(EVENT_ID, USER_ID);
                        successCnt.incrementAndGet();
                    } catch (CustomException e) {
                        if (e.getExceptionCode() == QueueExceptionCode.ALREADY_IN_QUEUE)
                            failCnt.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(successCnt.get()).isEqualTo(1);
            assertThat(failCnt.get()).isEqualTo(threadCount - 1);
        }

        private void when_setIfAbsent_returns(boolean value) {
            when(valueOps.setIfAbsent(eq(TOKEN_KEY), anyString(), eq(Duration.ofHours(24))))
                    .thenReturn(value);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getQueueStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getQueueStatus")
    class GetQueueStatus {

        @Test
        @DisplayName("저장된 토큰 없음(만료): INVALID_TOKEN 예외")
        void 저장_토큰_없음_예외() {
            when(valueOps.get(TOKEN_KEY)).thenReturn(null);

            assertExceptionCode(QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.getQueueStatus(EVENT_ID, USER_ID, TOKEN));
        }

        @Test
        @DisplayName("토큰 불일치: INVALID_TOKEN 예외")
        void 토큰_불일치_예외() {
            when(valueOps.get(TOKEN_KEY)).thenReturn("other-token");

            assertExceptionCode(QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.getQueueStatus(EVENT_ID, USER_ID, TOKEN));
        }

        @Test
        @DisplayName("활성 사용자: active=true 반환")
        void 활성_사용자() {
            long futureExpiry = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn((double) futureExpiry);

            QueueStatusResponse res = queueService.getQueueStatus(EVENT_ID, USER_ID, TOKEN);

            assertThat(res.active()).isTrue();
            assertThat(res.rank()).isEqualTo(0L);
            assertThat(res.estimateWaitTime()).isEqualTo(0L);
        }

        @Test
        @DisplayName("대기 중 사용자: active=false, 순위·대기시간 반환")
        void 대기중_사용자() {
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn(null);
            when(zSetOps.rank(WAIT_KEY, USER_ID_STR)).thenReturn(200L);

            QueueStatusResponse res = queueService.getQueueStatus(EVENT_ID, USER_ID, TOKEN);

            assertThat(res.active()).isFalse();
            assertThat(res.rank()).isEqualTo(201L);
            assertThat(res.estimateWaitTime()).isEqualTo(10L);  // (200/200)*10
        }

        @Test
        @DisplayName("만료된 활성 세션에서 대기열로 폴백")
        void 만료된_활성세션_대기열_폴백() {
            long pastExpiry = System.currentTimeMillis() - 1_000;
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn((double) pastExpiry);
            when(zSetOps.rank(WAIT_KEY, USER_ID_STR)).thenReturn(10L);

            QueueStatusResponse res = queueService.getQueueStatus(EVENT_ID, USER_ID, TOKEN);

            assertThat(res.active()).isFalse();
            assertThat(res.rank()).isEqualTo(11L);
        }

        @Test
        @DisplayName("활성 세션 만료 + 대기열 미존재: SESSION_EXPIRED 예외")
        void 세션_만료_예외() {
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn(null);
            when(zSetOps.rank(WAIT_KEY, USER_ID_STR)).thenReturn(null);

            assertExceptionCode(QueueExceptionCode.SESSION_EXPIRED,
                    () -> queueService.getQueueStatus(EVENT_ID, USER_ID, TOKEN));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // validateActiveUser
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateActiveUser")
    class ValidateActiveUser {

        @Test
        @DisplayName("저장된 토큰 없음: INVALID_TOKEN 예외")
        void 저장_토큰_없음_예외() {
            when(valueOps.get(TOKEN_KEY)).thenReturn(null);

            assertExceptionCode(QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.validateActiveUser(EVENT_ID, USER_ID, TOKEN));
        }

        @Test
        @DisplayName("토큰 불일치: INVALID_TOKEN 예외")
        void 토큰_불일치_예외() {
            when(valueOps.get(TOKEN_KEY)).thenReturn("other-token");

            assertExceptionCode(QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.validateActiveUser(EVENT_ID, USER_ID, TOKEN));
        }

        @Test
        @DisplayName("활성 큐 미등록: NOT_ACTIVE_USER 예외")
        void 활성큐_미등록_예외() {
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn(null);

            assertExceptionCode(QueueExceptionCode.NOT_ACTIVE_USER,
                    () -> queueService.validateActiveUser(EVENT_ID, USER_ID, TOKEN));
        }

        @Test
        @DisplayName("활성 세션 만료: NOT_ACTIVE_USER 예외")
        void 활성_세션_만료_예외() {
            long pastExpiry = System.currentTimeMillis() - 1_000;
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn((double) pastExpiry);

            assertExceptionCode(QueueExceptionCode.NOT_ACTIVE_USER,
                    () -> queueService.validateActiveUser(EVENT_ID, USER_ID, TOKEN));
        }

        @Test
        @DisplayName("유효한 활성 사용자: 세션 만료 시간 5분으로 갱신")
        void 유효한_활성_사용자_만료_갱신() {
            long futureExpiry = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn((double) futureExpiry);

            assertThatNoException().isThrownBy(
                    () -> queueService.validateActiveUser(EVENT_ID, USER_ID, TOKEN));

            verify(zSetOps).add(eq(ACTIVE_KEY), eq(USER_ID_STR), anyDouble());
            verify(valueOps).set(TOKEN_KEY, TOKEN, Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("활성 사용자 재호출: 만료 시간이 현재 시각 기준 5분 이후로 설정됨")
        void 유효한_활성_사용자_갱신_시각_검증() {
            long futureExpiry = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
            when(valueOps.get(TOKEN_KEY)).thenReturn(TOKEN);
            when(zSetOps.score(ACTIVE_KEY, USER_ID_STR)).thenReturn((double) futureExpiry);

            long before = System.currentTimeMillis();
            queueService.validateActiveUser(EVENT_ID, USER_ID, TOKEN);
            long after = System.currentTimeMillis();

            org.mockito.ArgumentCaptor<Double> scoreCaptor =
                    org.mockito.ArgumentCaptor.forClass(Double.class);
            verify(zSetOps).add(eq(ACTIVE_KEY), eq(USER_ID_STR), scoreCaptor.capture());

            long newExpiry = scoreCaptor.getValue().longValue();
            long expectedMin = before + Duration.ofMinutes(5).toMillis();
            long expectedMax = after  + Duration.ofMinutes(5).toMillis();
            assertThat(newExpiry).isBetween(expectedMin, expectedMax);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 공통 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    private void assertExceptionCode(QueueExceptionCode expected, org.junit.jupiter.api.function.Executable action) {
        assertThatThrownBy(action::execute)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getExceptionCode())
                .isEqualTo(expected);
    }

    // Mockito when(...) 을 lenient 없이 쓰기 위한 편의 메서드
    private static <T> org.mockito.stubbing.OngoingStubbing<T> when(T methodCall) {
        return org.mockito.Mockito.when(methodCall);
    }
}
