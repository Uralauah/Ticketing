package ticketing.global.exception;

import org.springframework.http.HttpStatus;

public interface ExceptionCode {
    String getCode();
    HttpStatus getStatus();
    String getMessage();
}
