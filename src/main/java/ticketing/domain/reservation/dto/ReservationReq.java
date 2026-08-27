package ticketing.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservationReq(
        @NotNull @Positive Long userId,
        @NotNull @Positive Long scheduleId,
        @NotNull @Positive Long seatId
) {
}
