package ticketing.domain.concert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.service.SeatStatusSseBroker.EmitterContext;
import ticketing.global.exception.CustomException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatStatusSseBrokerTest {

    private static final long SCHEDULE_ID = 1L;

    private final List<RecordingEmitter> createdEmitters =
            Collections.synchronizedList(new ArrayList<>());
    private final List<ExecutorService> executors = new ArrayList<>();

    /** 호출 스레드에서 바로 전송 (순서 검증을 결정적으로) */
    private final SeatStatusSseBroker broker = brokerWith(
            new ObjectMapper(),
            Runnable::run,
            100,
            1_000
    );

    @AfterEach
    void tearDown() {
        createdEmitters.forEach(RecordingEmitter::unblock);
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    @DisplayName("replay 중 도착한 이벤트는 버퍼링됐다가 replay 뒤에 version 순서대로 전송된다")
    void bufferedEventsAreFlushedAfterReplayInVersionOrder() {
        EmitterContext ctx = broker.subscribe(SCHEDULE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        // replay가 끝나기 전에 실시간 이벤트가 도착 (순서도 뒤섞여서)
        broker.broadcast(SCHEDULE_ID, change(6));
        broker.broadcast(SCHEDULE_ID, change(5));
        assertThat(emitter.events()).isEmpty();

        broker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(4, false, List.of(change(3), change(4))));

        assertThat(emitter.events()).extracting(SentEvent::name)
                .containsExactly("seat-status", "seat-status", "ready", "seat-status", "seat-status");
        assertThat(emitter.eventIds()).containsExactly("3", "4", "4", "5", "6");
    }

    @Test
    @DisplayName("replay가 이미 커버한 version의 버퍼 이벤트는 중복이므로 버려진다")
    void bufferedEventsAlreadyCoveredByReplayAreDropped() {
        EmitterContext ctx = broker.subscribe(SCHEDULE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        broker.broadcast(SCHEDULE_ID, change(4)); // replay 범위 안 (중복)
        broker.broadcast(SCHEDULE_ID, change(5)); // replay 범위 밖

        broker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(4, false, List.of(change(3), change(4))));

        assertThat(emitter.eventIds()).containsExactly("3", "4", "4", "5");
    }

    @Test
    @DisplayName("LIVE 전환 후의 이벤트는 즉시 전송된다")
    void eventsAfterReplayAreSentImmediately() {
        EmitterContext ctx = broker.subscribe(SCHEDULE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        broker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(2, false, List.of()));
        emitter.clear();

        broker.broadcast(SCHEDULE_ID, change(3));

        assertThat(emitter.events()).extracting(SentEvent::name).containsExactly("seat-status");
        assertThat(emitter.eventIds()).containsExactly("3");
        assertThat(emitter.events().get(0).data()).isEqualTo("{\"version\":3,\"seatId\":3,\"status\":\"AVAILABLE\"}");
    }

    @Test
    @DisplayName("full-sync가 필요하면 신호를 보내고, 그 뒤 버퍼는 이어서 전송된다")
    void fullSyncRequiredIsSignalledBeforeBufferFlush() {
        EmitterContext ctx = broker.subscribe(SCHEDULE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        broker.broadcast(SCHEDULE_ID, change(11));

        broker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(10, true, List.of()));

        assertThat(emitter.events()).extracting(SentEvent::name)
                .containsExactly("full-sync-required", "ready", "seat-status");
    }

    @Test
    @DisplayName("연결이 끊긴 emitter는 브로커에서 제거되어 더 이상 broadcast 대상이 아니다")
    void brokenEmitterIsRemoved() {
        EmitterContext ctx = broker.subscribe(SCHEDULE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        broker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(0, false, List.of()));
        emitter.failNextSends();

        broker.broadcast(SCHEDULE_ID, change(1)); // 전송 실패 -> 제거
        emitter.clear();
        broker.broadcast(SCHEDULE_ID, change(2));

        assertThat(emitter.events()).isEmpty();
        assertThat(broker.connectionCount()).isZero();
    }

    @Test
    @DisplayName("replay와 broadcast가 동시에 일어나도 이벤트가 유실되지 않는다")
    void concurrentBroadcastDuringReplayLosesNothing() throws Exception {
        int publishers = 4;
        int perPublisher = 50;
        long replayedUpTo = 0L;

        for (int round = 0; round < 20; round++) {
            createdEmitters.clear();
            EmitterContext ctx = broker.subscribe(SCHEDULE_ID);
            RecordingEmitter emitter = createdEmitters.get(0);

            ExecutorService pool = Executors.newFixedThreadPool(publishers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(publishers);
            for (int p = 0; p < publishers; p++) {
                long base = 1L + (long) p * perPublisher;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perPublisher; i++) {
                            broker.broadcast(SCHEDULE_ID, change(base + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            // broadcast 도중 replay 완료
            broker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(replayedUpTo, false, List.of()));

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            List<String> sentIds = emitter.eventIds().stream()
                    .filter(id -> !id.equals(String.valueOf(replayedUpTo)))
                    .collect(Collectors.toList());
            List<String> expected = LongStream.rangeClosed(1, (long) publishers * perPublisher)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.toList());

            assertThat(sentIds).containsExactlyInAnyOrderElementsOf(expected);

            broker.abort(SCHEDULE_ID, ctx, new IllegalStateException("teardown"));
        }
    }

    @Test
    @DisplayName("느린 클라이언트가 있어도 broadcast 즉시 반환, 다른 연결은 제때 수신")
    void 느린_클라이언트_격리() {
        SeatStatusSseBroker asyncBroker = brokerWith(new ObjectMapper(), newPool(4), 100, 1_000);
        EmitterContext slow = asyncBroker.subscribe(SCHEDULE_ID);
        EmitterContext fast = asyncBroker.subscribe(SCHEDULE_ID);
        asyncBroker.completeReplay(SCHEDULE_ID, slow, new SeatStatusDeltaRes(0, false, List.of()));
        asyncBroker.completeReplay(SCHEDULE_ID, fast, new SeatStatusDeltaRes(0, false, List.of()));
        RecordingEmitter slowEmitter = createdEmitters.get(0);
        RecordingEmitter fastEmitter = createdEmitters.get(1);
        slowEmitter.blockSends();

        long startedAt = System.nanoTime();
        for (long version = 1; version <= 3; version++) {
            asyncBroker.broadcast(SCHEDULE_ID, change(version));
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs).isLessThan(500);
        awaitTrue(() -> fastEmitter.eventIds().containsAll(List.of("1", "2", "3")));
        assertThat(slowEmitter.eventIds()).doesNotContain("1", "2", "3");

        slowEmitter.unblock();
        awaitTrue(() -> slowEmitter.eventIds().containsAll(List.of("1", "2", "3")));
        assertThat(slowEmitter.eventIds()).containsSubsequence("1", "2", "3");
    }

    @Test
    @DisplayName("송신 큐 초과: 연결을 끊어 재연결(Last-Event-ID)로 복구하도록 함")
    void 송신_큐_초과시_연결_종료() {
        SeatStatusSseBroker asyncBroker = brokerWith(new ObjectMapper(), newPool(2), 100, 3);
        EmitterContext ctx = asyncBroker.subscribe(SCHEDULE_ID);
        asyncBroker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(0, false, List.of()));
        RecordingEmitter emitter = createdEmitters.get(0);
        emitter.blockSends();

        asyncBroker.broadcast(SCHEDULE_ID, change(1)); // 전송 스레드가 꺼내 쓰다가 막힘
        awaitTrue(emitter::isBlocked);
        for (long version = 2; version <= 5; version++) { // 큐 상한 3 초과
            asyncBroker.broadcast(SCHEDULE_ID, change(version));
        }

        assertThat(asyncBroker.connectionCount()).isZero();
        emitter.unblock();
        awaitTrue(emitter::isCompleted);
    }

    @Test
    @DisplayName("heartbeat 주석은 한가한 LIVE 연결에만 전송, replay 중인 연결은 제외")
    void LIVE_연결에만_heartbeat() {
        EmitterContext live = broker.subscribe(SCHEDULE_ID);
        broker.subscribe(SCHEDULE_ID); // replay 중
        broker.completeReplay(SCHEDULE_ID, live, new SeatStatusDeltaRes(0, false, List.of()));
        RecordingEmitter liveEmitter = createdEmitters.get(0);
        RecordingEmitter replayingEmitter = createdEmitters.get(1);
        liveEmitter.clear();

        broker.sendHeartbeats();

        assertThat(liveEmitter.events()).extracting(SentEvent::name).containsExactly(":");
        assertThat(replayingEmitter.events()).isEmpty();
    }

    @Test
    @DisplayName("같은 변경은 연결 수와 무관하게 한 번만 직렬화")
    void broadcast당_한_번만_직렬화() {
        AtomicInteger serializations = new AtomicInteger();
        ObjectMapper countingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                serializations.incrementAndGet();
                return super.writeValueAsString(value);
            }
        };
        SeatStatusSseBroker countingBroker = brokerWith(countingMapper, Runnable::run, 100, 1_000);
        for (int i = 0; i < 5; i++) {
            EmitterContext ctx = countingBroker.subscribe(SCHEDULE_ID);
            countingBroker.completeReplay(
                    SCHEDULE_ID,
                    ctx,
                    new SeatStatusDeltaRes(0, false, List.of())
            );
        }
        serializations.set(0);

        countingBroker.broadcast(SCHEDULE_ID, change(1));

        assertThat(serializations.get()).isEqualTo(1);
        assertThat(createdEmitters)
                .allSatisfy(emitter -> assertThat(emitter.eventIds()).contains("1"));
    }

    @Test
    @DisplayName("일정당 연결 상한 초과: 503 거절")
    void 연결_상한_초과_거절() {
        SeatStatusSseBroker limited = brokerWith(new ObjectMapper(), Runnable::run, 2, 1_000);
        limited.subscribe(SCHEDULE_ID);
        limited.subscribe(SCHEDULE_ID);

        assertThatThrownBy(() -> limited.subscribe(SCHEDULE_ID))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getExceptionCode())
                                .isEqualTo(SeatExceptionCode.SEAT_STATUS_STREAM_UNAVAILABLE));
        assertThat(limited.connectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("종료 시 모든 연결 종료, 새 연결 거절")
    void 종료시_전체_연결_종료() {
        EmitterContext ctx = broker.subscribe(SCHEDULE_ID);
        broker.completeReplay(SCHEDULE_ID, ctx, new SeatStatusDeltaRes(0, false, List.of()));
        broker.subscribe(SCHEDULE_ID);

        broker.stop();

        assertThat(createdEmitters)
                .allSatisfy(emitter -> assertThat(emitter.isCompleted()).isTrue());
        assertThat(broker.connectionCount()).isZero();
        assertThatThrownBy(() -> broker.subscribe(SCHEDULE_ID)).isInstanceOf(CustomException.class);
    }

    private SeatStatusSseBroker brokerWith(ObjectMapper objectMapper, Executor executor,
                                           int maxConnections, int maxPendingEvents) {
        return new SeatStatusSseBroker(objectMapper, executor, maxConnections, maxPendingEvents) {
            @Override
            SseEmitter newEmitter() {
                RecordingEmitter emitter = new RecordingEmitter();
                createdEmitters.add(emitter);
                return emitter;
            }
        };
    }

    private ExecutorService newPool(int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        executors.add(pool);
        return pool;
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("조건이 3초 안에 만족되지 않았습니다.");
            }
            Thread.onSpinWait();
        }
    }

    private static SeatStatusChangeRes change(long version) {
        return new SeatStatusChangeRes(version, version, SeatStatus.AVAILABLE);
    }

    /** name이 ":"이면 주석(heartbeat) */
    private record SentEvent(String name, String id, Object data) {}

    /** 실제 HTTP 연결 없이 전송된 SSE 이벤트를 기록하는 emitter */
    private static class RecordingEmitter extends SseEmitter {

        private final List<SentEvent> events = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean failSends;
        private volatile CountDownLatch gate;
        private volatile boolean blocked;
        private volatile boolean completed;

        @Override
        public void send(SseEventBuilder builder) {
            CountDownLatch currentGate = gate;
            if (currentGate != null) {
                blocked = true;
                try {
                    currentGate.await(5, TimeUnit.SECONDS); // 소켓 쓰기에 막힌 느린 클라이언트 흉내
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    blocked = false;
                }
            }
            if (failSends) throw new IllegalStateException("broken pipe");
            events.add(parse(builder));
        }

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }

        void failNextSends() {
            failSends = true;
        }

        void blockSends() {
            gate = new CountDownLatch(1);
        }

        void unblock() {
            CountDownLatch currentGate = gate;
            gate = null;
            if (currentGate != null) {
                currentGate.countDown();
            }
        }

        boolean isBlocked() {
            return blocked;
        }

        boolean isCompleted() {
            return completed;
        }

        void clear() {
            events.clear();
        }

        List<SentEvent> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }

        List<String> eventIds() {
            return events().stream()
                    .map(SentEvent::id)
                    .filter(id -> id != null)
                    .collect(Collectors.toList());
        }

        private static SentEvent parse(SseEventBuilder builder) {
            String name = null;
            String id = null;
            Object payload = null;
            boolean payloadNext = false;

            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                Object data = entry.getData();
                if (payloadNext) {
                    payload = data;
                    payloadNext = false;
                    continue;
                }
                if (data instanceof String text) {
                    if (text.startsWith(":")) {
                        return new SentEvent(":", null, text);
                    }
                    // 여러 줄이 한 덩어리로 옴
                    for (String line : text.split("\n")) {
                        if (line.startsWith("event:")) name = line.substring("event:".length()).trim();
                        else if (line.startsWith("id:")) id = line.substring("id:".length()).trim();
                    }
                    if (text.endsWith("data:")) payloadNext = true;
                } else {
                    payload = data;
                }
            }
            return new SentEvent(name, id, payload);
        }
    }
}
