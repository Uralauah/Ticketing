package ticketing.domain.concert.service;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class SeatStatusSseBroker {

    // scheduleId -> emitter contexts
    private final Map<Long, CopyOnWriteArrayList<EmitterContext>> emitters = new ConcurrentHashMap<>();

    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L; // 30분

    /** replay 대기 중 쌓아둘 수 있는 최대 이벤트 수. 초과하면 full-sync로 전환한다. */
    private static final int MAX_PENDING_EVENTS = 1_000;

    /**
     * 연결을 등록한다. 이 시점부터 들어오는 실시간 이벤트는 유실되지 않고 컨텍스트 버퍼에 쌓이며,
     * {@link #completeReplay(long, EmitterContext, SeatStatusDeltaRes)} 호출 시 replay 뒤에 이어서 전송된다.
     */
    public EmitterContext subscribe(long scheduleId) {
        SseEmitter emitter = newEmitter();
        EmitterContext ctx = new EmitterContext(emitter, MAX_PENDING_EVENTS);

        emitters.computeIfAbsent(scheduleId, k -> new CopyOnWriteArrayList<>()).add(ctx);

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
        List<EmitterContext> list = emitters.get(scheduleId);
        if (list == null || list.isEmpty()) return;

        for (EmitterContext ctx : list) {
            // REPLAYING이면 버퍼링, LIVE면 즉시 전송. 상태 확인과 전송이 하나의 임계 구역 안에서 일어난다.
            if (!ctx.offer(change)) {
                remove(scheduleId, ctx);
            }
        }
    }

    /**
     * replay 결과 전송 → 대기 버퍼 flush → LIVE 전환을 하나의 임계 구역에서 처리한다.
     * delta 조회 자체는 임계 구역 밖(호출자)에서 끝내고 결과만 넘긴다.
     */
    public void completeReplay(long scheduleId, EmitterContext ctx, SeatStatusDeltaRes delta) {
        if (!ctx.replayAndGoLive(delta)) {
            remove(scheduleId, ctx);
        }
    }

    /** replay 준비 중 실패한 연결을 정리한다. */
    public void abort(long scheduleId, EmitterContext ctx, Throwable error) {
        ctx.abort(error);
        remove(scheduleId, ctx);
    }

    /** 테스트에서 emitter 구현을 갈아끼우기 위한 확장 지점 */
    SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    private void remove(long scheduleId, EmitterContext ctx) {
        // 비어 있는 리스트를 지우는 것과 새 구독이 리스트에 추가되는 것이 겹치지 않도록 원자적으로 처리한다.
        emitters.computeIfPresent(scheduleId, (k, list) -> {
            list.remove(ctx);
            return list.isEmpty() ? null : list;
        });
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

    /**
     * emitter 하나에 대한 전송 상태를 감싼다.
     * 모든 전송/상태 전이는 이 컨텍스트의 lock 안에서만 일어나므로
     * (1) 여러 스레드의 send()가 섞이지 않고, (2) replay와 실시간 이벤트의 순서가 뒤집히지 않는다.
     */
    public static final class EmitterContext {

        private enum State { REPLAYING, LIVE, CLOSED }

        private final SseEmitter emitter;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<SeatStatusChangeRes> buffer = new ArrayList<>();
        private final int maxPendingEvents;

        private State state = State.REPLAYING;
        private boolean overflowed;
        private Throwable lastSendError;

        EmitterContext(SseEmitter emitter, int maxPendingEvents) {
            this.emitter = emitter;
            this.maxPendingEvents = maxPendingEvents;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        /** @return 연결을 유지해도 되면 true, 끊어야 하면 false */
        boolean offer(SeatStatusChangeRes change) {
            lock.lock();
            try {
                switch (state) {
                    case CLOSED:
                        return false;
                    case REPLAYING:
                        bufferLocked(change);
                        return true;
                    default:
                        return sendChangeLocked(change) || failLocked();
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * replay 결과를 보내고, 그 사이 쌓인 이벤트를 version 오름차순으로 이어 보낸 뒤 LIVE로 전환한다.
         * flush와 상태 전환이 같은 임계 구역 안에 있으므로 "flush 직후 / 전환 직전" 유입이 유실되지 않는다.
         *
         * @return 연결을 유지해도 되면 true, 끊어야 하면 false
         */
        boolean replayAndGoLive(SeatStatusDeltaRes delta) {
            lock.lock();
            try {
                if (state != State.REPLAYING) return state == State.LIVE;

                boolean ok = delta.fullSyncRequired()
                        ? sendLocked("full-sync-required", delta.currentVersion(), delta, MediaType.APPLICATION_JSON)
                        : sendReplayChangesLocked(delta.changes());

                // ready: "currentVersion까지 따라잡았다"는 신호이므로 버퍼 flush보다 앞에 보낸다.
                if (ok) ok = sendLocked("ready", delta.currentVersion(), "ok", null);
                if (ok) ok = flushBufferLocked(delta.currentVersion());

                buffer.clear();
                overflowed = false;

                if (!ok) return failLocked();

                state = State.LIVE;
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** emitter가 이미 종료된 뒤(완료/타임아웃/에러) 호출된다. */
        void close() {
            lock.lock();
            try {
                state = State.CLOSED;
                buffer.clear();
                overflowed = false;
            } finally {
                lock.unlock();
            }
        }

        void abort(Throwable error) {
            lock.lock();
            try {
                if (state == State.CLOSED) return;
                state = State.CLOSED;
                buffer.clear();
                overflowed = false;
                completeWithError(emitter, error);
            } finally {
                lock.unlock();
            }
        }

        /** 전송에 실패한(=이미 끊긴) 연결을 정리한다. 항상 false를 돌려 호출자가 브로커에서 제거하게 한다. */
        private boolean failLocked() {
            state = State.CLOSED;
            buffer.clear();
            overflowed = false;
            completeWithError(emitter, lastSendError != null ? lastSendError : new IOException("SSE send failed"));
            return false;
        }

        private void bufferLocked(SeatStatusChangeRes change) {
            if (overflowed) return;
            if (buffer.size() >= maxPendingEvents) {
                // replay가 지나치게 길어진 경우. 메모리를 지키고 클라이언트에는 full-sync를 요구한다.
                overflowed = true;
                buffer.clear();
                return;
            }
            buffer.add(change);
        }

        private boolean sendReplayChangesLocked(List<SeatStatusChangeRes> changes) {
            for (SeatStatusChangeRes c : changes) {
                if (!sendChangeLocked(c)) return false;
            }
            return true;
        }

        /** replay가 커버한 version 이하의 버퍼 이벤트는 중복이므로 버린다. */
        private boolean flushBufferLocked(long replayedUpToVersion) {
            if (overflowed) {
                return sendLocked("full-sync-required", replayedUpToVersion,
                        new SeatStatusDeltaRes(replayedUpToVersion, true, List.of()),
                        MediaType.APPLICATION_JSON);
            }

            buffer.sort(Comparator.comparingLong(SeatStatusChangeRes::version));
            for (SeatStatusChangeRes c : buffer) {
                if (c.version() <= replayedUpToVersion) continue;
                if (!sendChangeLocked(c)) return false;
            }
            return true;
        }

        private boolean sendChangeLocked(SeatStatusChangeRes change) {
            return sendLocked("seat-status", change.version(), change, MediaType.APPLICATION_JSON);
        }

        private boolean sendLocked(String eventName, long eventId, Object data, MediaType mediaType) {
            try {
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                        .name(eventName)
                        .id(String.valueOf(eventId)); // 재연결 시 Last-Event-ID로 사용 가능
                emitter.send(mediaType == null ? event.data(data) : event.data(data, mediaType));
                return true;
            } catch (IOException | IllegalStateException e) {
                // 이미 끊긴 연결. 호출자가 브로커에서 제거한다.
                lastSendError = e;
                return false;
            }
        }
    }
}
