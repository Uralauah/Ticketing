package ticketing.domain.concert.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;

@Service
@RequiredArgsConstructor
public class SeatService {
    private final SeatRepository seatRepository;

    /** 예약 경로 전용 (SELECT ... FOR UPDATE) */
    public Seat findByIdForUpdate(Long seatId) {
        return seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new CustomException(SeatExceptionCode.SEAT_NOT_FOUND));
    }
}
