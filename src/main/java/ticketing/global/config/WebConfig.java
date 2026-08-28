package ticketing.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ticketing.domain.queue.web.ActiveUserInterceptor;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final ActiveUserInterceptor activeUserInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 이 경로 아래 새 API를 추가해도 대기열 검증 자동 적용
        registry.addInterceptor(activeUserInterceptor)
                .addPathPatterns("/api/v1/schedule/*/seat-status/**");
    }
}
