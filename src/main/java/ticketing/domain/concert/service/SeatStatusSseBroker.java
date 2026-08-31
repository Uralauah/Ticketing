package ticketing.domain.concert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.global.exception.CustomException;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * 일정별 SSE 연결 관리 및 좌석 변경 전파
 * 연결별 송신 큐 + 전송 전용 스레드 → 느린 클라이언트가 다른 연결의 전송을 막지 않음
 */
@Slf4j
@Component
public class SeatStatusSseBroker implements SmartLifecycle {

    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L; // 30분
    private static final int MAX_PENDING_EVENTS = 1_000; // 연결별 replay 버퍼·송신 큐 상한
    private static final int MAX_CONNECTIONS_PER_SCHEDULE = 5_000;
    private static final int SEND_THREADS = 8;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final ObjectMapper objectMapper;
    private final Executor sendExecutor;
    private final int maxConnectionsPerSchedule;
    private final int maxPendingEvents;

    // scheduleId -> emitter contexts
    private final Map<Long, Set<EmitterContext>> emitters = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile boolean stopped;
    private ScheduledExecutorService heartbeatScheduler;

    @Autowired
    public SeatStatusSseBroker(ObjectMapper objectMapper) {
        this(
                objectMapper,
                Executors.newFixedThreadPool(SEND_THREADS, daemonThreads("sse-send-")),
                MAX_CONNECTIONS_PER_SCHEDULE,
                MAX_PENDING_EVENTS
        );
    }

    // 테스트에서 전송 executor·상한 교체용
    SeatStatusSseBroker(ObjectMapper objectMapper,
                        Executor sendExecutor,
                        int maxConnectionsPerSchedule,
                        int maxPendingEvents) {
        this.objectMapper = objectMapper;
        this.sendExecutor = sendExecutor;
        this.maxConnectionsPerSchedule = maxConnectionsPerSchedule;
        this.maxPendingEvents = maxPendingEvents;
    }

    /** 등록 시점부터 들어온 이벤트는 버퍼에 쌓았다가 replay 뒤에 전송 */
    public EmitterContext subscribe(long scheduleId) {
        return subscribe(scheduleId, EMITTER_TIMEOUT_MS);
    }

    /** 입장 세션 만료 시각에 맞춰 닫을 때 사용 (최대 30분) */
    public EmitterContext subscribe(long scheduleId, long timeoutMs) {
        if (stopped) {
            throw new CustomException(SeatExceptionCode.SEAT_STATUS_STREAM_UNAVAILABLE);
        }

        SseEmitter emitter = newEmitter(Math.max(1, Math.min(timeoutMs, EMITTER_TIMEOUT_MS)));
        EmitterContext ctx = new EmitterContext(
                emitter,
                sendExecutor,
                maxPendingEvents,
                closed -> remove(scheduleId, closed)
        );

        boolean[] accepted = {false};
        // remove와 같은 키 잠금 안에서 추가 → 제거 중인 빈 집합에 들어가는 것 방지
        emitters.compute(scheduleId, (k, set) -> {
            Set<EmitterContext> contexts = set != null ? set : ConcurrentHashMap.newKeySet();
            if (contexts.size() < maxConnectionsPerSchedule) {
                contexts.add(ctx);
                accepted[0] = true;
            }
            return contexts.isEmpty() ? null : contexts;
        });
        if (!accepted[0]) {
            throw new CustomException(SeatExceptionCode.SEAT_STATUS_STREAM_UNAVAILABLE);
        }

        emitter.onCompletion(() -> {
            ctx.close();
            remove(scheduleId, ctx);
        });
        emitter.onTimeout(() -> {
            ctx.close();
            complete(emitter);
            remove(scheduleId, ctx);
        });
        emitter.onError(e -> {
            ctx.close();
            completeWithError(emitter, e);
            remove(scheduleId, ctx);
        });

        return ctx;
    }

    public void broadcast(long scheduleId, SeatStatusChangeRes change) {
        Set<EmitterContext> contexts = emitters.get(scheduleId);
        if (contexts == null || contexts.isEmpty()) {
            return;
        }

        SseEvent event = SseEvent.change(change.version(), toJson(change)); // 연결 수와 상관없이 한 번만 직렬화
        for (EmitterContext ctx : contexts) {
            if (!ctx.offer(event)) {
                remove(scheduleId, ctx);
            }
        }
    }

    /** replay 전송 → 대기 버퍼 flush → LIVE 전환 */
    public void completeReplay(long scheduleId, EmitterContext ctx, SeatStatusDeltaRes delta) {
        List<SseEvent> head = new ArrayList<>(delta.changes().size() + 1);
        if (delta.fullSyncRequired()) {
            head.add(SseEvent.fullSyncRequired(delta.currentVersion(), toJson(delta)));
        } else {
            for (SeatStatusChangeRes change : delta.changes()) {
                head.add(SseEvent.change(change.version(), toJson(change)));
            }
        }
        // ready(따라잡음 신호)는 버퍼 flush 전에 전송
        head.add(SseEvent.ready(delta.currentVersion()));

        if (!ctx.replayAndGoLive(head, delta.currentVersion(), this::fullSyncEvent)) {
            remove(scheduleId, ctx);
        }
    }

    public void abort(long scheduleId, EmitterContext ctx, Throwable error) {
        ctx.abort(error);
        remove(scheduleId, ctx);
    }

    /** 보낼 데이터가 없는 LIVE 연결에만 heartbeat 전송 */
    void sendHeartbeats() {
        for (Map.Entry<Long, Set<EmitterContext>> entry : emitters.entrySet()) {
            for (EmitterContext ctx : entry.getValue()) {
                if (!ctx.offerHeartbeat()) {
                    remove(entry.getKey(), ctx);
                }
            }
        }
    }

    public int connectionCount() {
        int count = 0;
        for (Set<EmitterContext> contexts : emitters.values()) {
            count += contexts.size();
        }
        return count;
    }

    // 테스트에서 emitter 교체용
    SseEmitter newEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    // 기본 phase라 웹 서버 graceful shutdown보다 먼저 연결 종료

    @Override
    public void start() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreads("sse-heartbeat-")
        );
        heartbeatScheduler.scheduleWithFixedDelay(
                this::sendHeartbeatsSafely,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        running = true;
    }

    @Override
    public void stop() {
        stopped = true;
        running = false;
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        int closed = 0;
        for (Map.Entry<Long, Set<EmitterContext>> entry : emitters.entrySet()) {
            for (EmitterContext ctx : entry.getValue()) {
                ctx.shutdown();
                remove(entry.getKey(), ctx);
                closed++;
            }
        }
        if (sendExecutor instanceof ExecutorService executor) {
            executor.shutdown();
            try {
                executor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("SSE 브로커 정지: 연결 {}개 종료", closed);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void sendHeartbeatsSafely() {
        try {
            sendHeartbeats();
        } catch (RuntimeException e) {
            log.warn("SSE heartbeat 실패", e);
        }
    }

    private SseEvent fullSyncEvent(long version) {
        return SseEvent.fullSyncRequired(
                version,
                toJson(new SeatStatusDeltaRes(version, true, List.of()))
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE 이벤트 직렬화 실패", e);
        }
    }

    private void remove(long scheduleId, EmitterContext ctx) {
        emitters.computeIfPresent(scheduleId, (k, set) -> {
            set.remove(ctx);
            return set.isEmpty() ? null : set;
        });
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 이미 완료된 emitter
        }
    }

    private static void completeWithError(SseEmitter emitter, Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (RuntimeException ignored) {
            // 이미 완료된 emitter
        }
    }

    /** 미리 직렬화한 SSE 이벤트 */
    record SseEvent(
            String name,
            long id,
            String data,
            boolean comment // true면 heartbeat 같은 주석 줄
    ) {
        static SseEvent change(long version, String json) {
            return new SseEvent("seat-status", version, json, false);
        }

        static SseEvent fullSyncRequired(long version, String json) {
            return new SseEvent("full-sync-required", version, json, false);
        }

        static SseEvent ready(long version) {
            return new SseEvent("ready", version, "ok", false);
        }

        static SseEvent heartbeat() {
            return new SseEvent(null, 0, "heartbeat", true);
        }
    }

    /**
     * 연결 하나의 전송 상태
     * - stateLock: 상태·버퍼 보호 (I/O 없이 짧게만 잡음)
     * - sendLock: emitter 쓰기 직렬화 → replay·실시간 이벤트 순서 보장
     * - 잠금 순서: sendLock → stateLock
     */
    public static final class EmitterContext {

        private enum State { REPLAYING, LIVE, CLOSED }

        private final SseEmitter emitter;
        private final Executor sendExecutor;
        private final int maxPendingEvents;
        private final Consumer<EmitterContext> onClosed;

        private final ReentrantLock sendLock = new ReentrantLock();
        private final Object stateLock = new Object();

        // stateLock 보호
        private State state = State.REPLAYING;
        private final List<SseEvent> replayBuffer = new ArrayList<>();
        private boolean replayOverflowed;
        private final ArrayDeque<SseEvent> outbox = new ArrayDeque<>();
        private boolean drainScheduled;

        // sendLock 보호
        private Throwable lastSendError;

        EmitterContext(SseEmitter emitter,
                       Executor sendExecutor,
                       int maxPendingEvents,
                       Consumer<EmitterContext> onClosed) {
            this.emitter = emitter;
            this.sendExecutor = sendExecutor;
            this.maxPendingEvents = maxPendingEvents;
            this.onClosed = onClosed;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        /** false면 연결 정리 필요 */
        boolean offer(SseEvent event) {
            boolean overflowed = false;
            synchronized (stateLock) {
                if (state == State.CLOSED) {
                    return false;
                }
                if (state == State.REPLAYING) {
                    bufferForReplayLocked(event);
                    return true;
                }
                if (outbox.size() >= maxPendingEvents) {
                    // 전송을 못 따라오는 클라이언트 → 연결 종료, 재연결(Last-Event-ID)로 복구
                    closeLocked();
                    overflowed = true;
                } else {
                    outbox.add(event);
                    if (drainScheduled) {
                        return true;
                    }
                    drainScheduled = true;
                }
            }
            if (overflowed) {
                // 쓰기에 막혀 있을 수 있어 구독 스레드에서 직접 닫지 않음
                runOnSender(() -> complete(emitter));
                return false;
            }
            return scheduleDrain();
        }

        boolean offerHeartbeat() {
            synchronized (stateLock) {
                if (state == State.CLOSED) {
                    return false;
                }
                // 보낼 데이터가 있으면 생략
                if (state == State.REPLAYING || drainScheduled || !outbox.isEmpty()) {
                    return true;
                }
                outbox.add(SseEvent.heartbeat());
                drainScheduled = true;
            }
            return scheduleDrain();
        }

        /**
         * replay 전송 후 그 사이 쌓인 이벤트를 version 순으로 보내고 LIVE 전환
         * 버퍼 확인과 LIVE 전환을 같은 stateLock 안에서 처리 → 유실·역전 방지
         */
        boolean replayAndGoLive(List<SseEvent> head,
                                long replayedUpToVersion,
                                LongFunction<SseEvent> fullSyncEvent) {
            sendLock.lock();
            try {
                synchronized (stateLock) {
                    if (state != State.REPLAYING) {
                        return state == State.LIVE;
                    }
                }
                for (SseEvent event : head) {
                    if (!sendLocked(event)) {
                        return failLocked();
                    }
                }

                while (true) {
                    List<SseEvent> pending;
                    boolean overflowed;
                    synchronized (stateLock) {
                        if (state == State.CLOSED) {
                            return false;
                        }
                        overflowed = replayOverflowed;
                        if (!overflowed && replayBuffer.isEmpty()) {
                            state = State.LIVE;
                            return true;
                        }
                        pending = new ArrayList<>(replayBuffer);
                        replayBuffer.clear();
                        replayOverflowed = false;
                    }

                    if (overflowed) {
                        // 버퍼 초과 → 스냅샷부터 다시 받도록 full-sync 요구
                        if (!sendLocked(fullSyncEvent.apply(replayedUpToVersion))) {
                            return failLocked();
                        }
                        continue;
                    }

                    // replay가 보낸 version 이하는 중복이라 제외
                    // 기준을 flush마다 올리면 늦게 온 낮은 version이 유실되므로 고정
                    pending.sort(Comparator.comparingLong(SseEvent::id));
                    for (SseEvent event : pending) {
                        if (event.id() <= replayedUpToVersion) {
                            continue;
                        }
                        if (!sendLocked(event)) {
                            return failLocked();
                        }
                    }
                }
            } finally {
                sendLock.unlock();
            }
        }

        // emitter 종료(완료/타임아웃/에러) 후 호출
        void close() {
            synchronized (stateLock) {
                closeLocked();
            }
        }

        void abort(Throwable error) {
            synchronized (stateLock) {
                if (state == State.CLOSED) return;
                closeLocked();
            }
            completeWithError(emitter, error);
        }

        // 서버 종료 시 정상 종료 → 클라이언트가 다른 인스턴스로 재연결
        void shutdown() {
            synchronized (stateLock) {
                if (state == State.CLOSED) {
                    return;
                }
                closeLocked();
            }
            runOnSender(() -> complete(emitter));
        }

        private boolean scheduleDrain() {
            try {
                sendExecutor.execute(this::drain);
                return true;
            } catch (RejectedExecutionException e) {
                // 전송 풀이 닫힌 상태(종료 중)
                close();
                complete(emitter);
                return false;
            }
        }

        private void drain() {
            sendLock.lock();
            try {
                while (true) {
                    SseEvent next;
                    synchronized (stateLock) {
                        if (state == State.CLOSED) {
                            outbox.clear();
                            drainScheduled = false;
                            return;
                        }
                        next = outbox.poll();
                        if (next == null) {
                            drainScheduled = false;
                            return;
                        }
                    }
                    if (!sendLocked(next)) {
                        failLocked();
                        return;
                    }
                }
            } finally {
                sendLock.unlock();
            }
        }

        private void runOnSender(Runnable task) {
            try {
                sendExecutor.execute(task);
            } catch (RejectedExecutionException e) {
                task.run();
            }
        }

        // sendLock 안에서만 호출
        private boolean failLocked() {
            synchronized (stateLock) {
                closeLocked();
            }
            completeWithError(
                    emitter,
                    lastSendError != null ? lastSendError : new IOException("SSE send failed")
            );
            onClosed.accept(this);
            return false;
        }

        private void closeLocked() {
            state = State.CLOSED;
            replayBuffer.clear();
            replayOverflowed = false;
            outbox.clear();
            drainScheduled = false;
        }

        private void bufferForReplayLocked(SseEvent event) {
            if (replayOverflowed) {
                return;
            }
            if (replayBuffer.size() >= maxPendingEvents) {
                // 버퍼 초과 → full-sync 요구
                replayOverflowed = true;
                replayBuffer.clear();
                return;
            }
            replayBuffer.add(event);
        }

        private boolean sendLocked(SseEvent event) {
            try {
                if (event.comment()) {
                    emitter.send(SseEmitter.event().comment(event.data()));
                } else {
                    emitter.send(SseEmitter.event()
                            .name(event.name())
                            .id(String.valueOf(event.id())) // 재연결 시 Last-Event-ID로 사용
                            .data(event.data()));
                }
                return true;
            } catch (IOException | IllegalStateException e) {
                // 이미 끊긴 연결, 정리는 호출자가 처리
                lastSendError = e;
                return false;
            }
        }
    }
}
