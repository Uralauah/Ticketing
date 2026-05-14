package ticketing.domain.concert.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ticketing.domain.concert.dto.SeatMapRes;
import ticketing.domain.concert.dto.SeatStatusDeltaRes;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.dto.SeatStatusSnapshotRes;
import ticketing.domain.concert.service.ConcertScheduleQueryService;
import ticketing.domain.concert.service.SeatStatusCacheService;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.concert.service.SeatStatusStreamService;
import ticketing.domain.queue.service.QueueService;

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
    private final QueueService queueService;

    @GetMapping("/{scheduleId}/seat-status/snapshot/{userId}")
    public ResponseEntity<SeatStatusSnapshotRes> snapshot(@PathVariable long scheduleId,
                                                          @PathVariable long userId,
                                                          @RequestHeader("X-Queue-Token") String token) {
        queueService.validateActiveUser(scheduleId, userId, token);

        return ResponseEntity.ok(seatStatusLiveService.snapshot(scheduleId));
    }

    @GetMapping("/{scheduleId}/seat-status/delta")
    public ResponseEntity<SeatStatusDeltaRes> delta(@PathVariable long scheduleId,
                                                    @RequestParam long since) {
        return ResponseEntity.ok(seatStatusLiveService.delta(scheduleId, since));
    }

    @GetMapping(value = "/{scheduleId}/seat-status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long scheduleId,
                             @RequestParam(required = false) Long since,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return seatStatusStreamService.stream(scheduleId, since, lastEventId);
    }
}