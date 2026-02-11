package ticketing.domain.concert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "seat")
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
    private SeatStatus status;

    @Builder
    public Seat(ConcertSchedule concertSchedule, String seatNum, int price){
        this.concertSchedule = concertSchedule;
        this.seatNum = seatNum;
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
    }
}
