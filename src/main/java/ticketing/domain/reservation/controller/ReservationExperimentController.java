package ticketing.domain.reservation.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ticketing.domain.reservation.dto.ReservationReq;
import ticketing.domain.reservation.dto.ReservationRes;
import ticketing.domain.reservation.service.ReservationService;

/** 대기열 없이 예약 경로 용량만 측정 (perf 프로필 전용) */
@Profile("perf")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiment/reservation")
public class ReservationExperimentController {
    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<ReservationRes> reserve(@Valid @RequestBody ReservationReq req) {
        return ResponseEntity.ok(reservationService.reserve(
                req.userId(),
                req.scheduleId(),
                req.seatId()
        ));
    }
}
