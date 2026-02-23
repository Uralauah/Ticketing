package ticketing.infra.redis.seatstatus;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.service.SeatStatusSseBroker;

import java.nio.charset.StandardCharsets;

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
        SeatStatusChangeRes change = codec.decode(body);

        broker.broadcast(scheduleId, change);
    }
}