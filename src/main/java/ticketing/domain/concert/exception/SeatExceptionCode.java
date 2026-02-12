package ticketing.domain.concert.exception;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import ticketing.global.exception.ExceptionCode;

@AllArgsConstructor
public enum SeatExceptionCode implements ExceptionCode {
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT-404-001", "해당 자리를 찾을 수 없습니다"),
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "SEAT-409-001", "해당 자리를 예약할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
