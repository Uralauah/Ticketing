package ticketing.domain.queue.dto;

public record QueueStatusResponse(
        boolean active,
        long rank,
        long estimateWaitTime
) {
    public static QueueStatusResponse activeToken(){
        return new QueueStatusResponse(true, 0, 0);
    }
}
