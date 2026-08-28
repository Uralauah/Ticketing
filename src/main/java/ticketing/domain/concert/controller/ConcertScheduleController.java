package ticketing.domain.concert.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatMapRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.dto.SeatStatusSnapshotRes;
import ticketing.domain.concert.service.ConcertScheduleQueryService;
import ticketing.domain.concert.service.SeatSnapshotCache;
import ticketing.domain.concert.service.SeatStatusCacheService;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.concert.service.SeatStatusStreamService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedule")
public class ConcertScheduleController {
//
//    private final ConcertScheduleQueryService queryService;
//    private final SeatStatusCacheService seatStatusCacheService;
//
//    @GetMapping("/{scheduleId}/seat-map")
//    public ResponseEntity<SeatMapRes> getSeatMap(@PathVariable long scheduleId) {
//        return ResponseEntity.ok(queryService.getSeatMap(scheduleId));
//    }
//
//    @GetMapping("/{scheduleId}/seat-status")
//    public ResponseEntity<List<SeatStatusRes>> getSeatStatus(@PathVariable long scheduleId) {
//        return ResponseEntity.ok(seatStatusCacheService.getSeatStatus(scheduleId));
//    }
    private final SeatStatusLiveService seatStatusLiveService;
    private final SeatStatusStreamService seatStatusStreamService;
    private final SeatSnapshotCache seatSnapshotCache;

    // seat-status API는 ActiveUserInterceptor에서 대기열 통과 여부 확인

    /** 응답 형식: {@link SeatStatusSnapshotRes} */
    @GetMapping("/{scheduleId}/seat-status/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable long scheduleId,
                                           @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false)
                                           String acceptEncoding,
                                           WebRequest request) {
        return SeatSnapshotResponses.of(seatSnapshotCache.get(scheduleId), acceptEncoding, request);
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