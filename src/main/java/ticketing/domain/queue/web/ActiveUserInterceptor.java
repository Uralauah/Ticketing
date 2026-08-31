package ticketing.domain.queue.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import ticketing.domain.queue.exception.QueueExceptionCode;
import ticketing.domain.queue.service.QueueService;
import ticketing.global.exception.CustomException;
import ticketing.global.exception.GlobalExceptionCode;

import java.util.Map;

/**
 * 좌석 상태 API는 대기열 통과 사용자만 접근 가능 (?userId + X-Queue-Token)
 * 컨트롤러가 아닌 경로 패턴(WebConfig)에 적용해 검증 누락 방지
 */
@Component
@RequiredArgsConstructor
public class ActiveUserInterceptor implements HandlerInterceptor {
    public static final String QUEUE_TOKEN_HEADER = "X-Queue-Token";
    /** EventSource는 헤더를 못 붙여 SSE에 한해 쿼리 파라미터 허용 */
    public static final String QUEUE_TOKEN_PARAM = "queueToken";
    public static final String USER_ID_PARAM = "userId";
    /** 세션 만료 시각(epoch ms), SSE 연결 종료 시각으로 사용 */
    public static final String ACTIVE_EXPIRES_AT_ATTRIBUTE = "ticketing.queue.activeExpiresAt";

    private final QueueService queueService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // SSE 종료 시 async 재디스패치는 재검증 생략
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
        );
        String scheduleId = pathVariables == null ? null : pathVariables.get("scheduleId");
        String userId = request.getParameter(USER_ID_PARAM);
        String token = resolveToken(request);
        if (scheduleId == null || userId == null || token == null || token.isBlank()) {
            throw new CustomException(QueueExceptionCode.QUEUE_CREDENTIALS_REQUIRED);
        }

        long expiresAt = queueService.validateActiveUser(
                parseId(scheduleId),
                parseId(userId),
                token
        );
        request.setAttribute(ACTIVE_EXPIRES_AT_ATTRIBUTE, expiresAt);
        return true;
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(QUEUE_TOKEN_HEADER);
        if (header != null) {
            return header;
        }
        // URL은 로그에 남을 수 있어 SSE 외에는 쿼리 파라미터 토큰 거부
        if (request.getRequestURI().endsWith("/stream")) {
            return request.getParameter(QUEUE_TOKEN_PARAM);
        }
        return null;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new CustomException(GlobalExceptionCode.INVALID_INPUT);
        }
    }
}
