package ticketing.domain.queue.exception;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import ticketing.global.exception.ExceptionCode;

@AllArgsConstructor
public enum QueueExceptionCode implements ExceptionCode {
    ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "QUEUE-409-001", "이미 대기열에 등록되어 있습니다."),
    ALREADY_ACTIVE(HttpStatus.CONFLICT, "QUEUE-409-002", "이미 입장한 상태입니다. 발급받은 토큰을 사용해주세요."),
    NOT_ACTIVE_USER(HttpStatus.BAD_REQUEST, "QUEUE-400-001", "대기열 통과가 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "QUEUE-401-001", "유효하지 않은 토큰입니다."),
    SESSION_EXPIRED(HttpStatus.GONE, "QUEUE-410-001", "입장 시간이 끝났거나 대기열에서 빠졌습니다. 대기열에 다시 등록해주세요.");

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
