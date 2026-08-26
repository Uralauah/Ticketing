package ticketing.domain.queue.dto;

/**
 * @param rank             대기 중일 때 1부터 시작하는 대기 순번(입장 상태면 0)
 * @param estimateWaitTime 예상 대기 시간(초)
 * @param pollAfterMs      다음 상태 조회까지 기다릴 시간(입장 상태면 0)
 * @param activeExpiresAt  입장 상태일 때 세션 만료 시각(epoch ms)
 */
public record QueueStatusResponse(
        boolean active,
        long rank,
        long estimateWaitTime,
        long pollAfterMs,
        long activeExpiresAt
) {
    public static QueueStatusResponse active(long activeExpiresAt) {
        return new QueueStatusResponse(true, 0, 0, 0, activeExpiresAt);
    }

    public static QueueStatusResponse waiting(long rank, long estimateWaitTime, long pollAfterMs) {
        return new QueueStatusResponse(false, rank, estimateWaitTime, pollAfterMs, 0);
    }
}
