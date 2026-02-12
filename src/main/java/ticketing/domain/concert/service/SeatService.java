package ticketing.domain.concert.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;

@Service
@AllArgsConstructor
public class SeatService {
    private final SeatRepository seatRepository;

    public Seat findById(Long seatId) {
        return seatRepository.findById(seatId)
                .orElseThrow(()->new CustomException(SeatExceptionCode.SEAT_NOT_FOUND));
    }
}
