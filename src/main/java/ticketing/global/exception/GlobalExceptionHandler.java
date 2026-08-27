package ticketing.global.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(CustomException.class)
    protected ResponseEntity<ExceptionRes> handleCustomException(final CustomException e){
        ExceptionCode exceptionCode = e.getExceptionCode();
        if (exceptionCode.getStatus().is5xxServerError()) {
            log.warn("[{}] {}", exceptionCode.getCode(), exceptionCode.getMessage());
        } else {
            // 좌석 선점 실패 등 4xx는 정상 흐름 → DEBUG
            log.debug("[{}] {}", exceptionCode.getCode(), exceptionCode.getMessage());
        }
        return toResponse(exceptionCode, exceptionCode.getMessage());
    }

    /** 처리되지 않은 DB 제약 위반은 409 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    protected ResponseEntity<ExceptionRes> handleDataIntegrityViolation(final DataIntegrityViolationException e) {
        log.warn("[DataIntegrityViolation] {}", e.getMostSpecificCause().getMessage());
        return toResponse(
                GlobalExceptionCode.DATA_CONFLICT,
                GlobalExceptionCode.DATA_CONFLICT.getMessage()
        );
    }

    /** 요청 형식 오류는 400 */
    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    protected ResponseEntity<ExceptionRes> handleBadRequest(final Exception e) {
        log.debug("[BadRequest] {}", e.getMessage());
        return toResponse(GlobalExceptionCode.INVALID_INPUT, badRequestMessage(e));
    }

    /** 응답 도중 클라이언트 이탈 (SSE 등) */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    protected void handleClientGone(final AsyncRequestNotUsableException e) {
        log.debug("[ClientGone] {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ExceptionRes> handleException(final Exception e) {
        // 404, 405 등 스프링 MVC가 정한 상태는 그대로 응답
        if (e instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            log.debug("[{}] {}", errorResponse.getStatusCode(), e.getMessage());
            return toResponse(errorResponse.getStatusCode());
        }
        log.error("[Exception] {}", e.getMessage(), e);
        return toResponse(
                GlobalExceptionCode.INTERNAL_SERVER_ERROR,
                GlobalExceptionCode.INTERNAL_SERVER_ERROR.getMessage()
        );
    }

    private ResponseEntity<ExceptionRes> toResponse(HttpStatusCode status) {
        GlobalExceptionCode code = switch (status.value()) {
            case 404 -> GlobalExceptionCode.NOT_FOUND;
            case 405 -> GlobalExceptionCode.METHOD_NOT_ALLOWED;
            default -> GlobalExceptionCode.INVALID_INPUT;
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ExceptionRes.of(code, code.getMessage()));
    }

    private ResponseEntity<ExceptionRes> toResponse(ExceptionCode exceptionCode, String message) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exceptionCode.getStatus())
                // SSE 요청에서 콘텐츠 협상 실패로 500이 나가는 것 방지
                .contentType(MediaType.APPLICATION_JSON);
        if (exceptionCode.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            // 즉시 재시도 쏠림 방지
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(ExceptionRes.of(exceptionCode, message));
    }

    private static String badRequestMessage(Exception e) {
        if (e instanceof MissingRequestHeaderException ex) {
            return "필수 헤더가 없습니다: " + ex.getHeaderName();
        }
        if (e instanceof MissingServletRequestParameterException ex) {
            return "필수 파라미터가 없습니다: " + ex.getParameterName();
        }
        if (e instanceof MethodArgumentTypeMismatchException ex) {
            return "값의 형식이 올바르지 않습니다: " + ex.getName();
        }
        if (e instanceof MethodArgumentNotValidException ex) {
            return ex.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getField)
                    .distinct()
                    .collect(Collectors.joining(", ", "값이 올바르지 않습니다: ", ""));
        }
        if (e instanceof HttpMessageNotReadableException) {
            return "요청 본문을 읽을 수 없습니다.";
        }
        return GlobalExceptionCode.INVALID_INPUT.getMessage();
    }
}
