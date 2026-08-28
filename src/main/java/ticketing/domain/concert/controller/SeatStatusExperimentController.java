package ticketing.domain.concert.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import ticketing.domain.concert.dto.SeatStatusSnapshotRes;
import ticketing.domain.concert.service.SeatSnapshotCache;
import ticketing.domain.concert.service.SeatStatusLiveService;

/** 부하 테스트 비교용 엔드포인트 (perf 프로필 전용) */
@Profile("perf")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiment/schedule")
public class SeatStatusExperimentController {
    private final SeatSnapshotCache seatSnapshotCache;
    private final SeatStatusLiveService seatStatusLiveService;

    /** 대기열 검증 없는 스냅샷 (locust/no_queue.py) */
    @GetMapping("/{scheduleId}/seat-status/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable long scheduleId,
                                           @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false)
                                           String acceptEncoding,
                                           WebRequest request) {
        return SeatSnapshotResponses.of(seatSnapshotCache.get(scheduleId), acceptEncoding, request);
    }

    /** 캐시 도입 이전 방식, 개선 전후 비교용 */
    @GetMapping("/{scheduleId}/seat-status/snapshot/legacy")
    public ResponseEntity<SeatStatusSnapshotRes> legacySnapshot(@PathVariable long scheduleId) {
        return ResponseEntity.ok(seatStatusLiveService.snapshot(scheduleId));
    }
}
