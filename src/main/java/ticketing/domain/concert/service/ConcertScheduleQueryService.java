package ticketing.domain.concert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import ticketing.domain.concert.dto.SeatMapRes;
import ticketing.domain.concert.dto.SeatMetaRes;
import ticketing.domain.concert.entity.ConcertSchedule;
import ticketing.domain.concert.exception.ScheduleExceptionCode;
import ticketing.domain.concert.repository.ConcertScheduleRepository;
import ticketing.domain.concert.repository.SeatRepository;
import ticketing.global.exception.CustomException;
import ticketing.global.web.PreSerializedBody;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ConcertScheduleQueryService {

    /** 좌석 배치도 응답 (내용 해시 = ETag) */
    public record SeatMap(
            byte[] json,
            byte[] gzipped,
            String eTag
    ) implements PreSerializedBody {}

    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final ObjectMapper objectMapper;

    private final Map<Long, SeatMap> seatMaps = new ConcurrentHashMap<>();

    /** 판매 중 바뀌지 않으므로 일정마다 한 번만 직렬화해 보관 */
    public SeatMap getSeatMap(long scheduleId) {
        SeatMap cached = seatMaps.get(scheduleId);
        if (cached != null) {
            return cached;
        }
        return seatMaps.computeIfAbsent(scheduleId, this::buildSeatMap);
    }

    private SeatMap buildSeatMap(long scheduleId) {
        ConcertSchedule schedule = concertScheduleRepository.findByIdWithConcert(scheduleId)
                .orElseThrow(() -> new CustomException(ScheduleExceptionCode.SCHEDULE_NOT_FOUND));
        List<SeatMetaRes> seats = seatRepository.findSeatMetaByScheduleId(scheduleId);
        SeatMapRes seatMap = new SeatMapRes(
                schedule.getConcert().getTitle(),
                schedule.getConcertDate(),
                seats
        );

        try {
            byte[] json = objectMapper.writeValueAsBytes(seatMap);
            return new SeatMap(
                    json,
                    PreSerializedBody.gzip(json),
                    "\"" + DigestUtils.md5DigestAsHex(json) + "\""
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("좌석 배치도 직렬화 실패: scheduleId=" + scheduleId, e);
        }
    }
}
