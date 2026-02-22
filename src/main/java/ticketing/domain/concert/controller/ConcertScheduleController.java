package ticketing.domain.concert.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ticketing.domain.concert.dto.SeatMapRes;
import ticketing.domain.concert.dto.SeatStatusRes;
import ticketing.domain.concert.service.ConcertScheduleQueryService;
import ticketing.domain.concert.service.SeatStatusCacheService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedule")
public class ConcertScheduleController {

    private final ConcertScheduleQueryService queryService;
    private final SeatStatusCacheService seatStatusCacheService;

    @GetMapping("/{scheduleId}/seat-map")
    public ResponseEntity<SeatMapRes> getSeatMap(@PathVariable long scheduleId) {
        return ResponseEntity.ok(queryService.getSeatMap(scheduleId));
    }

    @GetMapping("/{scheduleId}/seat-status")
    public ResponseEntity<List<SeatStatusRes>> getSeatStatus(@PathVariable long scheduleId) {
        return ResponseEntity.ok(seatStatusCacheService.getSeatStatus(scheduleId));
    }
}