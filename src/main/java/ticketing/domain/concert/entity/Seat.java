package ticketing.domain.concert.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.global.exception.CustomException;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 스키마는 db/migration에서 관리, 제약은 문서용
@Table(name = "seat", uniqueConstraints = @UniqueConstraint(
        name = "uk_seat_schedule_seat_num",
        columnNames = {"schedule_id", "seat_num"}
))
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

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    // 미지정 시 MySQL ENUM 기준으로 검증 → 마이그레이션의 VARCHAR(20)과 맞춤
    @JdbcTypeCode(SqlTypes.VARCHAR)
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
        // 다른 일정의 좌석은 상태와 무관하게 없는 좌석으로 처리
        validateSchedule(scheduleId);
        validateAvailable();
        status = SeatStatus.RESERVED;
    }

    public void validateSchedule(Long scheduleId) {
        if (this.concertSchedule == null || !this.concertSchedule.getId().equals(scheduleId)) {
            throw new CustomException(SeatExceptionCode.SEAT_NOT_FOUND);
        }
    }
}
