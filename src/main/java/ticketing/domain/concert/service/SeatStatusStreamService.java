package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;

@Service
@RequiredArgsConstructor
public class SeatStatusStreamService {

    private final SeatStatusLiveService liveService;
    private final SeatStatusSseBroker broker;

    public SseEmitter stream(long scheduleId, Long sinceParam, String lastEventIdHeader) {
        long since = resolveSince(sinceParam, lastEventIdHeader);

        // 1) 연결 등록(이 순간부터 실시간 이벤트 수신 가능)
        SseEmitter emitter = broker.subscribe(scheduleId);

        // 2) 놓친 이벤트 replay (중복 가능성은 있으나, version 기반이면 클라이언트에서 idempotent 처리 가능)
        try {
            SeatStatusDeltaRes delta = liveService.delta(scheduleId, since);

            if (delta.fullSyncRequired()) {
                // 클라이언트에게 "스냅샷 다시 받아라" 신호
                emitter.send(SseEmitter.event()
                        .name("full-sync-required")
                        .id(String.valueOf(delta.currentVersion()))
                        .data(delta));
            } else {
                for (SeatStatusChangeRes c : delta.changes()) {
                    emitter.send(SseEmitter.event()
                            .name("seat-status")
                            .id(String.valueOf(c.version()))
                            .data(c));
                }
            }

            // ready 이벤트(선택)
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .id(String.valueOf(delta.currentVersion()))
                    .data("ok"));

        } catch (Exception e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private long resolveSince(Long sinceParam, String lastEventIdHeader) {
        if (sinceParam != null) return Math.max(0, sinceParam);
        if (lastEventIdHeader != null && !lastEventIdHeader.isBlank()) {
            try { return Math.max(0, Long.parseLong(lastEventIdHeader)); }
            catch (Exception ignored) {}
        }
        return 0L;
    }
}