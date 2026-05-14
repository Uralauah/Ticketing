package ticketing.global.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static ticketing.global.queue.QueueProperties.ACTIVE_EVENTS_KEY;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueScheduler 단위 테스트")
@SuppressWarnings("unchecked")
class QueueSchedulerTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock SetOperations<String, String> setOps;
    @InjectMocks QueueScheduler queueScheduler;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    @DisplayName("활성 이벤트가 null이면 Lua 스크립트 미실행")
    void 활성_이벤트_null_스킵() {
        lenient().when(setOps.members(ACTIVE_EVENTS_KEY)).thenReturn(null);

        queueScheduler.promoteWaitingUsers();

        verify(redisTemplate).opsForSet();
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    @DisplayName("활성 이벤트가 비어있으면 Lua 스크립트 미실행")
    void 활성_이벤트_비어있음_스킵() {
        lenient().when(setOps.members(ACTIVE_EVENTS_KEY)).thenReturn(Collections.emptySet());

        queueScheduler.promoteWaitingUsers();

        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), any(List.class),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("활성 이벤트 N개: N번 Lua 스크립트 실행")
    void 활성_이벤트_수만큼_스크립트_실행() {
        lenient().when(setOps.members(ACTIVE_EVENTS_KEY)).thenReturn(Set.of("1", "2", "3"));
        doReturn(0L).when(redisTemplate).execute(
                any(DefaultRedisScript.class), any(List.class),
                any(), any(), any(), any(), any());

        queueScheduler.promoteWaitingUsers();

        verify(redisTemplate, times(3)).execute(
                any(DefaultRedisScript.class), any(List.class),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Lua 스크립트 KEYS: waitKey·activeKey·eventsKey 순서로 3개 전달")
    void Lua_스크립트_KEYS_검증() {
        String eventId = "42";
        lenient().when(setOps.members(ACTIVE_EVENTS_KEY)).thenReturn(Set.of(eventId));

        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(0L).when(redisTemplate).execute(
                any(DefaultRedisScript.class), keysCaptor.capture(),
                any(), any(), any(), any(), any());

        queueScheduler.promoteWaitingUsers();

        List<String> keys = keysCaptor.getValue();
        assertThat(keys).hasSize(3);
        assertThat(keys.get(0)).isEqualTo("queue:wait:42");
        assertThat(keys.get(1)).isEqualTo("queue:active:42");
        assertThat(keys.get(2)).isEqualTo(ACTIVE_EVENTS_KEY);
    }

    @Test
    @DisplayName("Lua 스크립트 ARGV: MAX_ACTIVE_USERS·BATCH_SIZE·eventId 값 검증")
    void Lua_스크립트_ARGV_검증() {
        String eventId = "42";
        lenient().when(setOps.members(ACTIVE_EVENTS_KEY)).thenReturn(Set.of(eventId));

        ArgumentCaptor<String> maxActiveCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> batchSizeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> expiryCaptor    = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nowCaptor       = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventIdCaptor   = ArgumentCaptor.forClass(String.class);

        doReturn(0L).when(redisTemplate).execute(
                any(DefaultRedisScript.class), any(List.class),
                maxActiveCaptor.capture(), batchSizeCaptor.capture(),
                expiryCaptor.capture(), nowCaptor.capture(), eventIdCaptor.capture());

        long before = System.currentTimeMillis();
        queueScheduler.promoteWaitingUsers();
        long after = System.currentTimeMillis();

        assertThat(maxActiveCaptor.getValue()).isEqualTo("1000");  // MAX_ACTIVE_USERS
        assertThat(batchSizeCaptor.getValue()).isEqualTo("200");   // BATCH_SIZE
        assertThat(eventIdCaptor.getValue()).isEqualTo(eventId);

        // expiry = now + 5분 인지 확인 (밀리초 단위)
        long expiry = Long.parseLong(expiryCaptor.getValue());
        long now    = Long.parseLong(nowCaptor.getValue());
        assertThat(expiry - now).isBetween(299_000L, 300_001L);

        // now 가 실제 실행 시각과 일치하는지 확인
        assertThat(now).isBetween(before, after);
    }
}
