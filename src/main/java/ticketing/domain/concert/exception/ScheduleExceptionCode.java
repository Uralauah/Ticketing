package ticketing.domain.concert.exception;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import ticketing.global.exception.ExceptionCode;

@AllArgsConstructor
public enum ScheduleExceptionCode implements ExceptionCode {
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHEDULE-404-001", "해당 공연 스케쥴을 찾을 수 없습니다");

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
