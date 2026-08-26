package ticketing.domain.queue.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ticketing.domain.queue.dto.QueueEntryResponse;
import ticketing.domain.queue.dto.QueueStatusResponse;
import ticketing.domain.queue.service.QueueService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/queue")
public class QueueController {
    private final QueueService queueService;

    @PostMapping("/{scheduleId}/entry")
    public ResponseEntity<QueueEntryResponse> enter(@PathVariable long scheduleId,
                                                    @RequestParam long userId){
        return ResponseEntity.ok(queueService.enterQueue(scheduleId, userId));
    }

    @GetMapping("/{scheduleId}/status")
    public ResponseEntity<QueueStatusResponse> status(@PathVariable long scheduleId,
                                                      @RequestParam long userId,
                                                      @RequestHeader("X-Queue-Token") String token) {
        return ResponseEntity.ok(queueService.getQueueStatus(scheduleId, userId, token));
    }

    /** 대기 취소 또는 입장 종료 */
    @DeleteMapping("/{scheduleId}/entry")
    public ResponseEntity<Void> leave(@PathVariable long scheduleId,
                                      @RequestParam long userId,
                                      @RequestHeader("X-Queue-Token") String token) {
        queueService.leave(scheduleId, userId, token);
        return ResponseEntity.noContent().build();
    }
}
