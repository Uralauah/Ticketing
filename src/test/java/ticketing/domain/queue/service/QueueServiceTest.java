package ticketing.domain.queue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.service.ScheduleValidator;
import ticketing.domain.queue.dto.QueueEntryResponse;
import ticketing.domain.queue.dto.QueueStatusResponse;
import ticketing.domain.queue.exception.QueueExceptionCode;
import ticketing.global.exception.CustomException;
import ticketing.global.exception.ExceptionCode;
import ticketing.global.queue.QueueProperties;
import ticketing.global.queue.QueueScheduler;
import ticketing.infra.redis.queue.QueueKeys;
import ticketing.infra.redis.queue.QueueRedisRepository;
import ticketing.infra.redis.queue.QueueRedisRepository.RecentAdmissions;
import ticketing.support.MutableClock;
import ticketing.support.RedisTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("대기열 (Redis 7)")
class QueueServiceTest extends RedisTestSupport {

    private static final long     SCHEDULE_ID = 1L;
    private static final Duration SESSION_TTL = Duration.ofMinutes(5);

    // 최대 입장 3명, 한 번에 2명씩 승격
    private final QueueProperties properties = new QueueProperties(
            3,
            2,
            Duration.ofSeconds(10),
            SESSION_TTL,
            Duration.ofHours(24),
            Duration.ofSeconds(60)
    );

    @Mock private ScheduleValidator scheduleValidator;

    // PEXPIREAT 등 Redis의 실제 시간 처리와 맞추기 위해 현재 시각에서 시작
    private final MutableClock clock = new MutableClock(Instant.now());
    private QueueService queueService;
    private QueueScheduler scheduler;

    @BeforeEach
    void setUp() {
        QueueRedisRepository repository = new QueueRedisRepository(redisTemplate);
        queueService = new QueueService(repository, properties, scheduleValidator, clock);
        scheduler = new QueueScheduler(repository, properties, clock);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 진입
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("진입")
    class Enter {

        @Test
        @DisplayName("정상 진입: 토큰·1부터 시작하는 순번·조회 간격 힌트 반환")
        void 정상_진입() {
            QueueEntryResponse response = queueService.enterQueue(SCHEDULE_ID, 100L);

            assertThat(response.token()).isNotBlank();
            assertThat(response.rank()).isEqualTo(1L);
            assertThat(response.pollAfterMs()).isEqualTo(1_000L);
            assertThat(response.estimateWaitTime()).isPositive();
            assertThat(redisTemplate.opsForSet().isMember(QueueKeys.ACTIVE_SCHEDULES, "1"))
                    .isTrue();
        }

        @Test
        @DisplayName("진입 순서대로 순번 부여")
        void 진입_순서대로_순번() {
            List<Long> ranks = new ArrayList<>();
            for (long userId = 1; userId <= 5; userId++) {
                ranks.add(queueService.enterQueue(SCHEDULE_ID, userId).rank());
            }
            assertThat(ranks).containsExactly(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        @DisplayName("대기 중 재진입: ALREADY_IN_QUEUE 예외")
        void 대기중_재진입_예외() {
            queueService.enterQueue(SCHEDULE_ID, 100L);

            assertCode(
                    QueueExceptionCode.ALREADY_IN_QUEUE,
                    () -> queueService.enterQueue(SCHEDULE_ID, 100L)
            );
        }

        @Test
        @DisplayName("입장 상태에서 재진입: ALREADY_ACTIVE 예외")
        void 입장중_재진입_예외() {
            queueService.enterQueue(SCHEDULE_ID, 100L);
            scheduler.promoteWaitingUsers();

            assertCode(
                    QueueExceptionCode.ALREADY_ACTIVE,
                    () -> queueService.enterQueue(SCHEDULE_ID, 100L)
            );
        }

        @Test
        @DisplayName("세션 종료 후 재진입 가능: 남아 있던 기존 토큰은 무효")
        void 세션_종료후_재진입() {
            String oldToken = queueService.enterQueue(SCHEDULE_ID, 100L).token();
            scheduler.promoteWaitingUsers();
            clock.advance(SESSION_TTL.plusSeconds(1));

            QueueEntryResponse reentered = queueService.enterQueue(SCHEDULE_ID, 100L);

            assertThat(reentered.token()).isNotEqualTo(oldToken);
            assertThat(reentered.rank()).isEqualTo(1L);
            assertCode(
                    QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.getQueueStatus(SCHEDULE_ID, 100L, oldToken)
            );
        }

        @Test
        @DisplayName("없는 일정: SCHEDULE_NOT_FOUND 예외, 대기열 키 미생성")
        void 없는_일정_진입_예외() {
            doThrow(new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND))
                    .when(scheduleValidator).validateExists(999L);

            assertCode(
                    ScheduleExceptionCode.SCHEDULE_NOT_FOUND,
                    () -> queueService.enterQueue(999L, 100L)
            );
            assertThat(redisTemplate.hasKey(QueueKeys.waiting(999L))).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 상태 조회
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("상태 조회")
    class Status {

        @Test
        @DisplayName("토큰 불일치: INVALID_TOKEN 예외")
        void 토큰_불일치_예외() {
            queueService.enterQueue(SCHEDULE_ID, 100L);

            assertCode(
                    QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.getQueueStatus(SCHEDULE_ID, 100L, "other")
            );
        }

        @Test
        @DisplayName("대기 중 사용자: 순번 반환, 조회 시각을 생존 신호로 기록")
        void 대기중_사용자_생존신호_기록() {
            queueService.enterQueue(SCHEDULE_ID, 1L);
            String token = queueService.enterQueue(SCHEDULE_ID, 2L).token();
            clock.advance(Duration.ofSeconds(30));

            QueueStatusResponse status = queueService.getQueueStatus(SCHEDULE_ID, 2L, token);

            assertThat(status.active()).isFalse();
            assertThat(status.rank()).isEqualTo(2L);
            assertThat(status.pollAfterMs()).isEqualTo(1_000L);
            assertThat(redisTemplate.opsForZSet().score(QueueKeys.seen(SCHEDULE_ID), "2"))
                    .isEqualTo((double) clock.millis());
        }

        @Test
        @DisplayName("활성 사용자: 만료 시각(승격 시점 + 세션 시간) 반환")
        void 활성_사용자_만료시각_반환() {
            String token = queueService.enterQueue(SCHEDULE_ID, 100L).token();
            long promotedAt = clock.millis();
            scheduler.promoteWaitingUsers();

            QueueStatusResponse status = queueService.getQueueStatus(SCHEDULE_ID, 100L, token);

            assertThat(status.active()).isTrue();
            assertThat(status.activeExpiresAt()).isEqualTo(promotedAt + SESSION_TTL.toMillis());
        }

        @Test
        @DisplayName("세션 만료 + 대기열 미존재: SESSION_EXPIRED 예외")
        void 세션_만료_예외() {
            String token = queueService.enterQueue(SCHEDULE_ID, 100L).token();
            scheduler.promoteWaitingUsers();
            clock.advance(SESSION_TTL.plusMillis(1));

            assertCode(
                    QueueExceptionCode.SESSION_EXPIRED,
                    () -> queueService.getQueueStatus(SCHEDULE_ID, 100L, token)
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 입장 검증
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("입장 검증")
    class ValidateActive {

        @Test
        @DisplayName("계속 호출해도 세션이 연장되지 않고 승격 시점 + 세션 시간에 끝난다")
        void sessionIsNotExtended() {
            String token = queueService.enterQueue(SCHEDULE_ID, 100L).token();
            long expiresAt = clock.millis() + SESSION_TTL.toMillis();
            scheduler.promoteWaitingUsers();

            for (int minute = 0; minute < 4; minute++) {
                clock.advance(Duration.ofMinutes(1));
                queueService.validateActiveUser(SCHEDULE_ID, 100L, token);
            }
            assertThat(redisTemplate.opsForZSet().score(QueueKeys.active(SCHEDULE_ID), "100"))
                    .isEqualTo((double) expiresAt);

            clock.advance(Duration.ofMinutes(1).plusMillis(1));
            assertCode(
                    QueueExceptionCode.NOT_ACTIVE_USER,
                    () -> queueService.validateActiveUser(SCHEDULE_ID, 100L, token)
            );
        }

        @Test
        @DisplayName("대기 중: NOT_ACTIVE_USER 예외, 토큰 불일치: INVALID_TOKEN 예외")
        void 대기중_또는_토큰_불일치_예외() {
            String token = queueService.enterQueue(SCHEDULE_ID, 100L).token();

            assertCode(
                    QueueExceptionCode.NOT_ACTIVE_USER,
                    () -> queueService.validateActiveUser(SCHEDULE_ID, 100L, token)
            );
            assertCode(
                    QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.validateActiveUser(SCHEDULE_ID, 100L, "other")
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 승격
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("승격")
    class Promotion {

        @Test
        @DisplayName("빈 슬롯과 배치 크기만큼만 승격")
        void 빈_슬롯만큼_승격() {
            enterAll(1, 5);

            scheduler.promoteWaitingUsers();
            assertThat(activeCount()).isEqualTo(2L);
            scheduler.promoteWaitingUsers();
            assertThat(activeCount()).isEqualTo(3L);
            scheduler.promoteWaitingUsers();
            assertThat(activeCount()).isEqualTo(3L);
            assertThat(redisTemplate.opsForZSet().range(QueueKeys.waiting(SCHEDULE_ID), 0, -1))
                    .containsExactly("4", "5");
        }

        @Test
        @DisplayName("세션이 끝난 자리는 다음 대기자에게 돌아간다")
        void expiredSessionsFreeSlots() {
            enterAll(1, 3);
            scheduler.promoteWaitingUsers();
            scheduler.promoteWaitingUsers();
            clock.advance(Duration.ofMinutes(4));
            String token = queueService.enterQueue(SCHEDULE_ID, 4L).token();
            scheduler.promoteWaitingUsers();
            assertThat(queueService.getQueueStatus(SCHEDULE_ID, 4L, token).active()).isFalse();

            clock.advance(Duration.ofMinutes(1).plusSeconds(1));
            queueService.getQueueStatus(SCHEDULE_ID, 4L, token); // 대기자는 계속 상태 조회 (생존 신호)
            scheduler.promoteWaitingUsers();

            assertThat(queueService.getQueueStatus(SCHEDULE_ID, 4L, token).active()).isTrue();
            assertThat(activeCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("생존 신호 끊긴 대기자: 승격 없이 대기열에서 제거")
        void 이탈_대기자_제거() {
            String leftToken = queueService.enterQueue(SCHEDULE_ID, 1L).token();  // 탭을 닫고 떠난 사용자
            String waitingToken = queueService.enterQueue(SCHEDULE_ID, 2L).token();
            clock.advance(Duration.ofSeconds(50));
            queueService.getQueueStatus(SCHEDULE_ID, 2L, waitingToken);           // 계속 조회 중인 사용자
            clock.advance(Duration.ofSeconds(11));

            scheduler.promoteWaitingUsers();

            assertThat(queueService.getQueueStatus(SCHEDULE_ID, 2L, waitingToken).active())
                    .isTrue();
            assertThat(redisTemplate.opsForZSet().score(QueueKeys.waiting(SCHEDULE_ID), "1"))
                    .isNull();
            assertCode(
                    QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.getQueueStatus(SCHEDULE_ID, 1L, leftToken)
            );
            assertThat(queueService.enterQueue(SCHEDULE_ID, 1L).rank()).isEqualTo(1L);
        }

        @Test
        @DisplayName("승격 시 토큰 수명을 세션 만료에 맞춤")
        void 승격시_토큰_수명_조정() {
            queueService.enterQueue(SCHEDULE_ID, 100L);

            scheduler.promoteWaitingUsers();

            Long ttlMs = redisTemplate.getExpire(
                    QueueKeys.token(SCHEDULE_ID, 100L),
                    java.util.concurrent.TimeUnit.MILLISECONDS
            );
            assertThat(ttlMs)
                    .isBetween(SESSION_TTL.minusSeconds(5).toMillis(), SESSION_TTL.toMillis());
        }

        @Test
        @DisplayName("대기자·입장자 없는 일정은 순회 대상에서 제외")
        void 빈_일정_순회_제외() {
            queueService.enterQueue(SCHEDULE_ID, 100L);
            scheduler.promoteWaitingUsers();
            clock.advance(SESSION_TTL.plusSeconds(1));

            scheduler.promoteWaitingUsers();

            assertThat(redisTemplate.opsForSet().isMember(QueueKeys.ACTIVE_SCHEDULES, "1"))
                    .isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 나가기
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("나가기")
    class Leave {

        @Test
        @DisplayName("입장 중 나가기: 슬롯 즉시 반납, 다음 대기자 입장")
        void 입장중_나가기_슬롯_반납() {
            List<String> tokens = enterAll(1, 4);
            scheduler.promoteWaitingUsers();
            scheduler.promoteWaitingUsers();

            queueService.leave(SCHEDULE_ID, 1L, tokens.get(0));
            scheduler.promoteWaitingUsers();

            assertThat(queueService.getQueueStatus(SCHEDULE_ID, 4L, tokens.get(3)).active())
                    .isTrue();
            assertThat(activeCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("대기 중 나가기: 대기열에서 제거, 재진입 가능")
        void 대기중_나가기() {
            String token = queueService.enterQueue(SCHEDULE_ID, 100L).token();

            queueService.leave(SCHEDULE_ID, 100L, token);

            assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.waiting(SCHEDULE_ID))).isZero();
            assertThat(queueService.enterQueue(SCHEDULE_ID, 100L).rank()).isEqualTo(1L);
        }

        @Test
        @DisplayName("다른 사람 토큰: INVALID_TOKEN 예외, 대기열 유지")
        void 토큰_불일치_예외() {
            queueService.enterQueue(SCHEDULE_ID, 100L);

            assertCode(
                    QueueExceptionCode.INVALID_TOKEN,
                    () -> queueService.leave(SCHEDULE_ID, 100L, "other")
            );
            assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.waiting(SCHEDULE_ID)))
                    .isEqualTo(1L);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 매진
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("대기 시간 예측과 조회 간격")
    class Estimate {

        @Test
        @DisplayName("최근 입장 기록 있음: 실제 입장 속도로 예측")
        void 최근_입장_속도로_예측() {
            long now = clock.millis();
            // 1초 전에 200명이 입장: 관측 구간 = 1초 + 승격 주기 10초 → 초당 약 18명
            RecentAdmissions recent = new RecentAdmissions(200, now - 1_000);

            assertThat(queueService.estimateWaitSeconds(100, recent, now)).isEqualTo(6L);
        }

        @Test
        @DisplayName("기록 없음: 배치 속도와 세션 회전 속도 중 느린 쪽으로 예측")
        void 기록_없으면_설정_속도로_예측() {
            // 최대 3명 / 세션 5분 = 초당 0.01명 < 배치 2명 / 10초
            RecentAdmissions none = new RecentAdmissions(0, -1);

            assertThat(queueService.estimateWaitSeconds(1, none, clock.millis())).isEqualTo(100L);
        }

        @Test
        @DisplayName("승격 시 입장 기록, 진입 응답의 대기 시간 예측에 사용")
        void 승격시_입장_기록() {
            enterAll(1, 2);
            scheduler.promoteWaitingUsers();
            clock.advance(Duration.ofSeconds(1));

            QueueEntryResponse late = queueService.enterQueue(SCHEDULE_ID, 3L);

            // 2명 / 11초 → 1명에 6초
            assertThat(late.estimateWaitTime()).isEqualTo(6L);
        }

        @Test
        @DisplayName("순번이 멀수록 조회 간격 증가")
        void 순번별_조회_간격() {
            assertThat(QueueService.pollAfterMs(1)).isEqualTo(1_000L);
            assertThat(QueueService.pollAfterMs(100)).isEqualTo(1_000L);
            assertThat(QueueService.pollAfterMs(101)).isEqualTo(3_000L);
            assertThat(QueueService.pollAfterMs(1_000)).isEqualTo(3_000L);
            assertThat(QueueService.pollAfterMs(1_001)).isEqualTo(10_000L);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 공통 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> enterAll(long fromUserId, long toUserId) {
        return LongStream.rangeClosed(fromUserId, toUserId)
                .mapToObj(userId -> queueService.enterQueue(SCHEDULE_ID, userId).token())
                .toList();
    }

    private long activeCount() {
        Long count = redisTemplate.opsForZSet().zCard(QueueKeys.active(SCHEDULE_ID));
        return count == null ? 0 : count;
    }

    private static void assertCode(ExceptionCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        CustomException.class,
                        e -> assertThat(e.getExceptionCode()).isEqualTo(expected)
                );
    }
}
