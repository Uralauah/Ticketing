package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;
import ticketing.domain.concert.service.SeatStatusSseBroker.EmitterContext;

@Service
@RequiredArgsConstructor
public class SeatStatusStreamService {

    private final SeatStatusLiveService liveService;
    private final SeatStatusSseBroker broker;

    public SseEmitter stream(long scheduleId, Long sinceParam, String lastEventIdHeader) {
        // 없는 일정(404)·초기화 중(503)은 SSE 연결 전에 HTTP 오류로 응답
        liveService.ensureInitialized(scheduleId);
        Long since = resolveSince(sinceParam, lastEventIdHeader);

        // 1) 연결 등록, 이후 들어온 이벤트는 버퍼에 쌓임
        EmitterContext ctx = broker.subscribe(scheduleId);

        try {
            // 2) 놓친 이벤트 조회, 기준 version이 없으면 스냅샷부터 받도록 함
            SeatStatusDeltaRes delta = since == null
                    ? liveService.fullSyncRequired(scheduleId)
                    : liveService.delta(scheduleId, since);

            // 3) replay 전송 → 버퍼 flush → LIVE 전환
            broker.completeReplay(scheduleId, ctx, delta);
        } catch (Exception e) {
            broker.abort(scheduleId, ctx, e);
        }

        return ctx.emitter();
    }

    /** since 또는 Last-Event-ID, 없거나 잘못된 값이면 null */
    private Long resolveSince(Long sinceParam, String lastEventIdHeader) {
        if (sinceParam != null) {
            return sinceParam >= 0 ? sinceParam : null;
        }
        if (lastEventIdHeader != null && !lastEventIdHeader.isBlank()) {
            try {
                long lastEventId = Long.parseLong(lastEventIdHeader.trim());
                return lastEventId >= 0 ? lastEventId : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
