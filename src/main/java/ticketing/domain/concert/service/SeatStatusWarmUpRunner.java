package ticketing.domain.concert.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 판매 오픈 전 좌석 상태 미리 적재 (ticketing.seat-status.warm-up-schedule-ids)
 * 오픈 직후 요청 경로에서 DB 적재가 일어나는 것 방지
 */
@Slf4j
@Component
public class SeatStatusWarmUpRunner implements ApplicationRunner {
    private final SeatStatusInitializer initializer;
    private final List<Long> scheduleIds;

    public SeatStatusWarmUpRunner(SeatStatusInitializer initializer,
                                  @Value("${ticketing.seat-status.warm-up-schedule-ids:}") List<Long> scheduleIds) {
        this.initializer = initializer;
        this.scheduleIds = scheduleIds;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Long scheduleId : scheduleIds) {
            try {
                initializer.ensureInitialized(scheduleId);
            } catch (RuntimeException e) {
                // 실패해도 기동은 계속 (첫 요청에서 적재)
                log.warn("좌석 상태 미리 적재 실패: scheduleId={}", scheduleId, e);
            }
        }
    }
}
