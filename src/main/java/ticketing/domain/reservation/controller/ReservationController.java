package ticketing.domain.reservation.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ticketing.domain.queue.service.QueueService;
import ticketing.domain.queue.web.ActiveUserInterceptor;
import ticketing.domain.reservation.dto.ReservationReq;
import ticketing.domain.reservation.dto.ReservationRes;
import ticketing.domain.reservation.service.ReservationService;

@RestController
@RequestMapping("/api/v1/reservation")
@RequiredArgsConstructor
public class ReservationController {
    private final ReservationService reservationService;
    private final QueueService queueService;

    /** 대기열을 통과한 본인만 예약 가능 (userId와 토큰을 함께 검증) */
    @PostMapping
    public ResponseEntity<ReservationRes> reserve(@Valid @RequestBody ReservationReq reservationReq,
                                                  @RequestHeader(ActiveUserInterceptor.QUEUE_TOKEN_HEADER)
                                                  String queueToken) {
        queueService.validateActiveUser(
                reservationReq.scheduleId(),
                reservationReq.userId(),
                queueToken
        );

        ReservationRes reservation = reservationService.reserve(
                reservationReq.userId(),
                reservationReq.scheduleId(),
                reservationReq.seatId()
        );

        // 커밋 후 반납 → 롤백된 예약으로 슬롯만 사라지는 것 방지
        queueService.releaseAfterReservation(
                reservationReq.scheduleId(),
                reservationReq.userId(),
                queueToken
        );
        return ResponseEntity.ok(reservation);
    }
}
