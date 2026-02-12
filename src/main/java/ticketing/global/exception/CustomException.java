package ticketing.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CustomException extends RuntimeException{
    private final ExceptionCode exceptionCode;

    @Override
    public String getMessage(){
        return exceptionCode.getMessage();
    }
}
