package ticketing.infra.redis.seatstatus;

import org.springframework.stereotype.Component;
import ticketing.domain.concert.dto.SeatStatusChangeRes;
import ticketing.domain.concert.entity.SeatStatus;

@Component
public class SeatStatusChangeCodec {

    public String encode(long version, long seatId, SeatStatus status) {
        return version + "|" + seatId + "|" + status.name();
    }

    public SeatStatusChangeRes decode(String raw) {
        String[] p = raw.split("\\|");
        long v = Long.parseLong(p[0]);
        long seatId = Long.parseLong(p[1]);
        SeatStatus status = SeatStatus.valueOf(p[2]);
        return new SeatStatusChangeRes(v, seatId, status);
    }
}