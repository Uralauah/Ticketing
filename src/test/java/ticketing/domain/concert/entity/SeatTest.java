package ticketing.domain.concert.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ticketing.domain.concert.exception.SeatExceptionCode;
import ticketing.global.exception.CustomException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatTest {

    @Test
    @DisplayName("다른 일정의 좌석: 예약 여부와 무관하게 SEAT_NOT_FOUND 예외")
    void 다른_일정_좌석_예외() {
        Seat seat = seatOfSchedule(1L);
        seat.reserve(1L);

        assertThatThrownBy(() -> seat.reserve(2L))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getExceptionCode())
                                .isEqualTo(SeatExceptionCode.SEAT_NOT_FOUND));
    }

    @Test
    @DisplayName("같은 일정의 이미 예약된 좌석: SEAT_NOT_AVAILABLE 예외")
    void 이미_예약된_좌석_예외() {
        Seat seat = seatOfSchedule(1L);
        seat.reserve(1L);

        assertThatThrownBy(() -> seat.reserve(1L))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getExceptionCode())
                                .isEqualTo(SeatExceptionCode.SEAT_NOT_AVAILABLE));
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.RESERVED);
    }

    private static Seat seatOfSchedule(long scheduleId) {
        ConcertSchedule schedule = ConcertSchedule.builder()
                .concert(Concert.builder().title("공연").build())
                .concertDate(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(schedule, "id", scheduleId);
        return Seat.builder().concertSchedule(schedule).seatNum("A-1").price(10_000).build();
    }
}
