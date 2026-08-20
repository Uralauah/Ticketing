package ticketing.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ticketing.domain.concert.entity.Seat;
import ticketing.domain.user.entity.User;

import java.time.LocalDateTime;

/**
 * 더블 부킹 방지
 * 1) 좌석 행 비관적 락 후 상태 확인
 * 2) 활성 예약(PENDING, PAID)에만 걸리는 UNIQUE 제약 (최종 방어선)
 */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name = "reservation")
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(nullable = false)
    private LocalDateTime reservationTime;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private ReservationStatus status;

    @Builder
    public Reservation(User user, Seat seat){
        this.user = user;
        this.seat = seat;
        this.price = seat.getPrice();
        this.reservationTime = LocalDateTime.now();
        this.status = ReservationStatus.PAID;
    }

    public static Reservation of(User user, Seat seat){
        return Reservation.builder()
                .user(user)
                .seat(seat)
                .build();
    }
}
