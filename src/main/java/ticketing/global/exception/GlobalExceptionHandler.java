package ticketing.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(CustomException.class)
    protected ResponseEntity<?> handleCustomException(final CustomException e){
        ExceptionCode exceptionCode = e.getExceptionCode();
        log.error("[Custom Exception] {}",exceptionCode.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exceptionCode.getStatus());
        if (exceptionCode.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            // 즉시 재시도 쏠림 방지
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(ExceptionRes.from(exceptionCode));
    }

    /** 처리되지 않은 DB 제약 위반은 409 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    protected ResponseEntity<?> handleDataIntegrityViolation(final DataIntegrityViolationException e) {
        log.warn("[DataIntegrityViolation] {}", e.getMostSpecificCause().getMessage());
        ExceptionCode error = GlobalExceptionCode.DATA_CONFLICT;
        return ResponseEntity.status(error.getStatus()).body(ExceptionRes.from(error));
    }

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<?> handleException(final Exception e) {
        log.error("[Exception] {}", e.getMessage());
        ExceptionCode error = GlobalExceptionCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(error.getStatus()).body(ExceptionRes.from(error));
    }
}
