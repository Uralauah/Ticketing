package ticketing.infra.redis.seatstatus;

import org.springframework.stereotype.Component;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.entity.SeatStatus;

/** 변경 로그·Pub/Sub 메시지 형식: version|seatId|status|publishedAt (Lua와 동일) */
@Component
public class SeatStatusChangeCodec {

    public String encode(long version, long seatId, SeatStatus status, long publishedAt) {
        return version + "|" + seatId + "|" + status.name() + "|" + publishedAt;
    }

    public SeatStatusChangeRes decode(String raw) {
        String[] p = raw.split("\\|");
        long v = Long.parseLong(p[0]);
        long seatId = Long.parseLong(p[1]);
        SeatStatus status = SeatStatus.valueOf(p[2]);
        // publishedAt이 없는 이전 형식 변경 로그 호환
        long publishedAt = p.length > 3 ? Long.parseLong(p[3]) : 0L;
        return new SeatStatusChangeRes(v, seatId, status, publishedAt);
    }
}
