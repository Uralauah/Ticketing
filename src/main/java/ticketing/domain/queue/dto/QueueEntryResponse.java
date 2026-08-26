package ticketing.domain.queue.dto;

public record QueueEntryResponse(
        String token,
        long rank,
        long estimateWaitTime, // 초 단위
        long pollAfterMs // 다음 상태 조회까지 대기 시간
) {
}
