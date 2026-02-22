package ticketing.domain.concert.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ticketing.domain.concert.dto.ConcertScheduleRes;
import ticketing.domain.concert.service.ConcertScheduleService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedule")
public class ConcertScheduleController {
    private final ConcertScheduleService concertScheduleService;

    @GetMapping("/{scheduleId}/seats")
    public ResponseEntity<ConcertScheduleRes> getSeats(@PathVariable long scheduleId) {
        ConcertScheduleRes res = concertScheduleService.getSeats(scheduleId);
        return ResponseEntity.ok(res);
    }
}
