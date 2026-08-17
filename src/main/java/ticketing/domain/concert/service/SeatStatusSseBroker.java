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

    /** replay 대기 버퍼 상한, 초과 시 full-sync 요구 */
    private static final int MAX_PENDING_EVENTS = 1_000;

    /** 등록 시점부터 들어온 이벤트는 버퍼에 쌓았다가 replay 뒤에 전송 */
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
            // REPLAYING이면 버퍼링, LIVE면 즉시 전송 (상태 확인과 전송을 같은 락 안에서 처리)
            if (!ctx.offer(change)) {
                remove(scheduleId, ctx);
            }
        }
    }

    /**
     * replay 전송 → 대기 버퍼 flush → LIVE 전환을 한 임계 구역에서 처리
     * delta 조회는 호출자가 락 밖에서 처리
     */
    public void completeReplay(long scheduleId, EmitterContext ctx, SeatStatusDeltaRes delta) {
        if (!ctx.replayAndGoLive(delta)) {
            remove(scheduleId, ctx);
        }
    }

    public void abort(long scheduleId, EmitterContext ctx, Throwable error) {
        ctx.abort(error);
        remove(scheduleId, ctx);
    }

    // 테스트에서 emitter 교체용
    SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    private void remove(long scheduleId, EmitterContext ctx) {
        // 빈 리스트 제거와 새 구독 추가가 겹치지 않도록 원자적으로 처리
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
     * 연결 하나의 전송 상태
     * 전송·상태 전이를 lock 안에서만 처리 → send() 섞임, replay·실시간 이벤트 순서 역전 방지
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

        /** false면 연결 정리 필요 */
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
         * replay 전송 후 그 사이 쌓인 이벤트를 version 순으로 보내고 LIVE 전환
         * flush와 LIVE 전환을 같은 임계 구역에서 처리 → 유실 방지
         */
        boolean replayAndGoLive(SeatStatusDeltaRes delta) {
            lock.lock();
            try {
                if (state != State.REPLAYING) return state == State.LIVE;

                boolean ok = delta.fullSyncRequired()
                        ? sendLocked("full-sync-required", delta.currentVersion(), delta, MediaType.APPLICATION_JSON)
                        : sendReplayChangesLocked(delta.changes());

                // ready(따라잡음 신호)는 버퍼 flush 전에 전송
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

        // emitter 종료(완료/타임아웃/에러) 후 호출
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

        /** 끊긴 연결 정리, 항상 false 반환 → 호출자가 브로커에서 제거 */
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
                // 버퍼 초과 → full-sync 요구
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

        /** replay가 보낸 version 이하는 중복이라 제외 */
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
                // 이미 끊긴 연결, 정리는 호출자가 처리
                lastSendError = e;
                return false;
            }
        }
    }
}
