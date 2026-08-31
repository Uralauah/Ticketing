package ticketing.domain.queue.dto;

public record QueueStatusResponse(
        boolean active,
        long rank,
        long estimateWaitTime, // 초 단위
        long pollAfterMs, // 입장 또는 매진이면 0
        long activeExpiresAt, // 세션 만료 시각(epoch ms)
        boolean soldOut // 매진이면 대기 중단
) {
    public static QueueStatusResponse active(long activeExpiresAt, boolean soldOut) {
        return new QueueStatusResponse(true, 0, 0, 0, activeExpiresAt, soldOut);
    }

    public static QueueStatusResponse waiting(long rank, long estimateWaitTime, long pollAfterMs) {
        return new QueueStatusResponse(false, rank, estimateWaitTime, pollAfterMs, 0, false);
    }

    public static QueueStatusResponse soldOut(long rank) {
        return new QueueStatusResponse(false, rank, 0, 0, 0, true);
    }
}
