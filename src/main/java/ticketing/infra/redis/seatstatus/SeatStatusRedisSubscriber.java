package ticketing.infra.redis.seatstatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.service.SeatStatusSseBroker;

import java.nio.charset.StandardCharsets;

/** 좌석 변경 메시지를 SSE 브로커로 전달 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatStatusRedisSubscriber implements MessageListener {

    private final SeatStatusSseBroker broker;
    private final SeatStatusChangeCodec codec;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        Long scheduleId = SeatStatusTopic.parseScheduleId(channel);
        if (scheduleId == null) return;

        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            SeatStatusChangeRes change = codec.decode(body);
            broker.broadcast(scheduleId, change);
        } catch (RuntimeException e) {
            // 메시지 하나의 오류로 구독 스레드가 멈추는 것 방지
            log.warn("좌석 상태 메시지 처리 실패: channel={}, body={}", channel, body, e);
        }
    }
}
