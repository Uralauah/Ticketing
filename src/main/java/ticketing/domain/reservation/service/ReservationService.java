package ticketing.domain.reservation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.service.SeatService;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.reservation.entity.Reservation;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.service.UserService;

@Service
@RequiredArgsConstructor
public class ReservationService {
    private final UserService userService;
    private final SeatService seatService;
    private final ReservationRepository reservationRepository;
    private final SeatStatusLiveService seatStatusLiveService;

    @Transactional
    public void reserve(Long userId, Long scheduleId, Long seatId){
        User user = userService.findById(userId);

        Seat seat = seatService.findByIdForUpdate(seatId);
        seat.reserve(scheduleId);
        user.reserve(seat.getPrice());

        Reservation reservation = Reservation.of(user, seat);
        reservationRepository.save(reservation);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                seatStatusLiveService.recordChange(scheduleId, seatId, SeatStatus.RESERVED);
            }
        });
    }
}
