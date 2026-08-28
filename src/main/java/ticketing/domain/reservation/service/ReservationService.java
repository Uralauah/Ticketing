package ticketing.domain.reservation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.concert.entity.SeatStatus;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.domain.concert.service.SeatService;
import ticketing.domain.concert.service.SeatStatusLiveService;
import ticketing.domain.reservation.dto.ReservationRes;
import ticketing.domain.reservation.entity.Reservation;
import ticketing.domain.reservation.repository.ReservationRepository;
import ticketing.domain.user.entity.User;
import ticketing.domain.user.service.UserService;
import ticketing.global.exception.CustomException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {
    private final UserService userService;
    private final SeatService seatService;
    private final ReservationRepository reservationRepository;
    private final SeatStatusLiveService seatStatusLiveService;

    @Transactional
    public ReservationRes reserve(Long userId, Long scheduleId, Long seatId){
        // 락 순서 좌석 → 사용자 고정 (교착 상태 방지)
        Seat seat = seatService.findByIdForUpdate(seatId);
        seat.reserve(scheduleId);
        userService.deductPoint(userId, seat.getPrice());
        User user = userService.getReference(userId);

        Reservation reservation;
        try {
            // 제약 위반을 커밋 전에 감지
            reservation = reservationRepository.saveAndFlush(Reservation.of(user, seat));
        } catch (DataIntegrityViolationException e) {
            // 락을 거쳤는데도 위반이면 락 없는 쓰기 경로가 있다는 뜻
            log.warn("활성 예약 UNIQUE 제약 위반: scheduleId={}, seatId={}", scheduleId, seatId);
            throw new CustomException(SeatExceptionCode.SEAT_NOT_AVAILABLE);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                seatStatusLiveService.recordChange(scheduleId, seatId, SeatStatus.RESERVED);
            }
        });
        return ReservationRes.from(reservation);
    }
}
