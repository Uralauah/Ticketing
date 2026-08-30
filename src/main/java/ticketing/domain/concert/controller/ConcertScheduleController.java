package ticketing.domain.concert.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatMapRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;
import ticketing.domain.concert.dto.SeatStatusSnapshotRes;
import ticketing.domain.concert.service.ConcertScheduleQueryService;
import ticketing.domain.concert.service.SeatSnapshotCache;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.concert.service.SeatStatusStreamService;
import ticketing.global.web.PreSerializedResponses;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedule")
public class ConcertScheduleController {
    /** 판매 중 바뀌지 않음 → 공유 캐시(CDN, 프록시) 허용 */
    private static final CacheControl SEAT_MAP_CACHE =
            CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
    /** 입장한 사용자 전용 → 공유 캐시 금지, 매번 ETag로 재검증 */
    private static final CacheControl SEAT_STATUS_CACHE = CacheControl.noCache().cachePrivate();

    private final ConcertScheduleQueryService queryService;
    private final SeatStatusLiveService seatStatusLiveService;
    private final SeatStatusStreamService seatStatusStreamService;
    private final SeatSnapshotCache seatSnapshotCache;

    /** 응답 형식: {@link SeatMapRes}, 대기열 검증 없이 공개 */
    @GetMapping("/{scheduleId}/seat-map")
    public ResponseEntity<byte[]> seatMap(@PathVariable long scheduleId,
                                          @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false)
                                          String acceptEncoding,
                                          WebRequest request) {
        return PreSerializedResponses.of(
                queryService.getSeatMap(scheduleId),
                SEAT_MAP_CACHE,
                acceptEncoding,
                request
        );
    }

    // seat-status API는 ActiveUserInterceptor에서 대기열 통과 여부 확인

    /** 응답 형식: {@link SeatStatusSnapshotRes} */
    @GetMapping("/{scheduleId}/seat-status/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable long scheduleId,
                                           @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false)
                                           String acceptEncoding,
                                           WebRequest request) {
        return PreSerializedResponses.of(
                seatSnapshotCache.get(scheduleId),
                SEAT_STATUS_CACHE,
                acceptEncoding,
                request
        );
    }

    @GetMapping("/{scheduleId}/seat-status/delta")
    public ResponseEntity<SeatStatusDeltaRes> delta(@PathVariable long scheduleId,
                                                    @RequestParam long since) {
        return ResponseEntity.ok(seatStatusLiveService.delta(scheduleId, since));
    }

    /** since(스냅샷 version)도 Last-Event-ID도 없으면 replay 없이 full-sync-required 전송 */
    @GetMapping(value = "/{scheduleId}/seat-status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long scheduleId,
                             @RequestParam(required = false) Long since,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletResponse response) {
        // 프록시(nginx) 버퍼링 방지
        response.setHeader("X-Accel-Buffering", "no");
        return seatStatusStreamService.stream(scheduleId, since, lastEventId);
    }
}