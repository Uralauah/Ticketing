package ticketing.domain.concert.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.service.SeatStatusSseBroker.EmitterContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class SeatStatusSseBrokerTest {

    private static final long SCHEDULE_ID = 1L;

    private final List<RecordingEmitter> createdEmitters = new ArrayList<>();

    private final SeatStatusSseBroker broker = new SeatStatusSseBroker() {
        @Override
        SseEmitter newEmitter() {
            RecordingEmitter emitter = new RecordingEmitter();
            createdEmitters.add(emitter);
            return emitter;
        }
    };

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
            // replay 완료가 broadcast 홍수 한가운데에서 일어나도록 한다
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

    private static SeatStatusChangeRes change(long version) {
        return new SeatStatusChangeRes(version, version, SeatStatus.AVAILABLE);
    }

    private record SentEvent(String name, String id, Object data) {}

    /** 실제 HTTP 연결 없이 전송된 SSE 이벤트를 기록하는 emitter */
    private static class RecordingEmitter extends SseEmitter {

        private final List<SentEvent> events = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean failSends;

        @Override
        public void send(SseEventBuilder builder) {
            if (failSends) throw new IllegalStateException("broken pipe");
            events.add(parse(builder));
        }

        void failNextSends() {
            failSends = true;
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
            return events().stream().map(SentEvent::id).collect(Collectors.toList());
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
                    // "event:seat-status\nid:5\ndata:" 처럼 여러 줄이 한 덩어리로 온다
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
