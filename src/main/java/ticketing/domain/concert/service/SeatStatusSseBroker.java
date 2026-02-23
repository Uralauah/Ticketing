package ticketing.domain.concert.service;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatStatusChangeRes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class SeatStatusSseBroker {

    // scheduleId -> emitters
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L; // 30분

    public SseEmitter subscribe(long scheduleId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        emitters.computeIfAbsent(scheduleId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(scheduleId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(scheduleId, emitter);
        });
        emitter.onError(e -> {
            emitter.completeWithError(e);
            remove(scheduleId, emitter);
        });

        return emitter;
    }

    public void broadcast(long scheduleId, SeatStatusChangeRes change) {
        List<SseEmitter> list = emitters.get(scheduleId);
        if (list == null || list.isEmpty()) return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("seat-status")
                        .id(String.valueOf(change.version())) // 재연결 시 Last-Event-ID로 사용 가능
                        .data(change, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                remove(scheduleId, emitter);
            }
        }
    }

    private void remove(long scheduleId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(scheduleId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(scheduleId);
    }
}