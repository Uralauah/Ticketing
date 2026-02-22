package ticketing.domain.concert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.global.exception.CustomException;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "seat", indexes = {
        @Index(name = "idx_seat_schedule", columnList = "concert_schedule_id"),
        @Index(name = "idx_seat_schedule_seatnum", columnList = "concert_schedule_id, seat_num")
})
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ConcertSchedule concertSchedule;

    @Column(nullable = false)
    private String seatNum;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SeatStatus status;

    @Builder
    public Seat(ConcertSchedule concertSchedule, String seatNum, int price) {
        this.concertSchedule = concertSchedule;
        this.seatNum = seatNum;
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
    }

    public void validateAvailable() {
        if (status != SeatStatus.AVAILABLE) {
            throw new CustomException(SeatExceptionCode.SEAT_NOT_AVAILABLE);
        }
    }

    public void reserve(Long scheduleId) {
        validateAvailable();
        validateSchedule(scheduleId);
        status = SeatStatus.RESERVED;
    }

    public void validateSchedule(Long scheduleId) {
        if (this.concertSchedule == null || !this.concertSchedule.getId().equals(scheduleId)) {
            throw new CustomException(SeatExceptionCode.SEAT_NOT_FOUND);
        }
    }
}
