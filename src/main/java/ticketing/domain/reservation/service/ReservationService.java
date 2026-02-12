package ticketing.domain.reservation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.service.ConcertScheduleService;
import ticketing.domain.concert.service.SeatService;
import ticketing.domain.reservation.entity.Reservation;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.service.UserService;

@Service
@RequiredArgsConstructor
public class ReservationService {
    private final UserService userService;
    private final ConcertScheduleService scheduleService;
    private final SeatService seatService;
    private final ReservationRepository reservationRepository;

    @Transactional
    public void reserve(Long userId, Long scheduleId, Long seatId){
        User user = userService.findById(userId);

        Seat seat = seatService.findById(seatId);
        seat.reserve(scheduleId);
        user.reserve(seat.getPrice());

        Reservation reservation = Reservation.of(user, seat);
        reservationRepository.save(reservation);
    }
}
