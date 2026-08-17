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
        long since = resolveSince(sinceParam, lastEventIdHeader);

        // 1) 연결 등록, 이후 들어온 이벤트는 버퍼에 쌓임
        EmitterContext ctx = broker.subscribe(scheduleId);

        try {
            // 2) 놓친 이벤트 조회 (Redis I/O라 락 밖에서)
            SeatStatusDeltaRes delta = liveService.delta(scheduleId, since);

            // 3) replay 전송 → 버퍼 flush → LIVE 전환
            broker.completeReplay(scheduleId, ctx, delta);
        } catch (Exception e) {
            broker.abort(scheduleId, ctx, e);
        }

        return ctx.emitter();
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
