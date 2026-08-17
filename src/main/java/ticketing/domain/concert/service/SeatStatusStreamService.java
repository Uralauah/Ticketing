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

        // 1) 연결 등록. 이 순간부터 들어오는 실시간 이벤트는 컨텍스트 버퍼에 쌓인다(아직 전송하지 않는다).
        EmitterContext ctx = broker.subscribe(scheduleId);

        try {
            // 2) 놓친 이벤트 조회. Redis I/O이므로 emitter 락 밖에서 끝낸다.
            SeatStatusDeltaRes delta = liveService.delta(scheduleId, since);

            // 3) replay 전송 → 버퍼 flush → LIVE 전환. 브로커가 하나의 임계 구역에서 처리하므로
            //    replay 도중 도착한 이벤트가 유실되거나 순서가 뒤집히지 않는다.
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
