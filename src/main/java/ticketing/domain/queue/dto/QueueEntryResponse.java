package ticketing.domain.queue.dto;

public record QueueEntryResponse(
        String token,
        long rank,
        long estimateWaitTime
) {
}
