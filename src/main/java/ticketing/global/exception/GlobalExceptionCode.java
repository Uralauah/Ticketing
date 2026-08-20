package ticketing.global.exception;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum GlobalExceptionCode implements ExceptionCode{
    DATA_CONFLICT(HttpStatus.CONFLICT, "GLOBAL-409-001", "요청이 이미 저장된 데이터와 충돌합니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "GLOBAL-500-001", "서버 에러입니다. 서버 팀에 문의해주세요.");

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
