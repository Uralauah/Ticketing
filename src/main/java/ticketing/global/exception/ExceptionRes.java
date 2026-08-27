package ticketing.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExceptionRes(
        String code,
        String message
) {
    public static ExceptionRes from(ExceptionCode exceptionCode) {
        return of(exceptionCode, exceptionCode.getMessage());
    }

    public static ExceptionRes of(ExceptionCode exceptionCode, String message) {
        return ExceptionRes.builder()
                .code(exceptionCode.getCode())
                .message(message)
                .build();
    }
}
