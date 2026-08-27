package ticketing.domain.reservation.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ticketing.domain.reservation.dto.ReservationReq;
import ticketing.domain.reservation.service.ReservationService;

@RestController
@RequestMapping("/api/v1/reservation")
@RequiredArgsConstructor
public class ReservationController {
    private final ReservationService reservationService;

    @PostMapping()
    public ResponseEntity<?> reserve(@Valid @RequestBody ReservationReq reservationReq) {
        reservationService.reserve(reservationReq.userId(), reservationReq.scheduleId(), reservationReq.seatId());
        return ResponseEntity.ok("성공적으로 예약되었습니다.");
    }
}
