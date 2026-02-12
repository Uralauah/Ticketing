package ticketing.domain.user.exception;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import ticketing.global.exception.ExceptionCode;

@AllArgsConstructor
public enum UserExceptionCode implements ExceptionCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-404-001", "해당 사용자를 찾을 수 없습니다"),
    USER_CANT_PAY(HttpStatus.BAD_REQUEST, "USER-400-001", "결제를 할 수 없습니다");

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
