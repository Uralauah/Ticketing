package ticketing.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
        return ResponseEntity.status(exceptionCode.getStatus()).body(ExceptionRes.from(exceptionCode));
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
