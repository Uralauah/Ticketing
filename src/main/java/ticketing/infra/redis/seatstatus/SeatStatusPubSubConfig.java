package ticketing.infra.redis.seatstatus;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 좌석 상태 변경 채널(seatStatus:topic:*) 구독
 * Boot가 리스너 컨테이너를 자동 생성하지 않아 직접 등록
 */
@Configuration
public class SeatStatusPubSubConfig {
    /**
     * 기본 executor는 메시지마다 새 스레드 → 단일 스레드로 version 순서 보장
     * 빈으로 등록하면 Boot의 applicationTaskExecutor가 꺼지므로 직접 관리
     */
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "seat-status-subscriber");
        thread.setDaemon(true);
        return thread;
    });

    @Bean
    public RedisMessageListenerContainer seatStatusListenerContainer(RedisConnectionFactory connectionFactory,
                                                                     SeatStatusRedisSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(subscriberExecutor);
        container.addMessageListener(subscriber, new PatternTopic(SeatStatusTopic.PATTERN));
        return container;
    }

    @PreDestroy
    void shutdownSubscriberExecutor() {
        subscriberExecutor.shutdown();
    }
}
