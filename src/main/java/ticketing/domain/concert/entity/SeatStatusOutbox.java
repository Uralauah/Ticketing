package ticketing.domain.concert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** Redis 반영이 확인되지 않은 좌석 상태 변경 */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name = "seat_status_outbox")
public class SeatStatusOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long scheduleId;

    @Column(nullable = false)
    private long seatId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private SeatStatus status;

    // 릴레이 유예 시간 판단용
    @Column(nullable = false)
    private LocalDateTime createdAt;

    private SeatStatusOutbox(long scheduleId,
                             long seatId,
                             SeatStatus status,
                             LocalDateTime createdAt) {
        this.scheduleId = scheduleId;
        this.seatId = seatId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static SeatStatusOutbox of(long scheduleId,
                                      long seatId,
                                      SeatStatus status,
                                      LocalDateTime createdAt) {
        return new SeatStatusOutbox(scheduleId, seatId, status, createdAt);
    }
}
