package ticketing.infra.redis.queue;

/** 대기열 Redis 키 (단일 Redis 전제, Cluster 전환 시 해시 태그 필요) */
public final class QueueKeys {
    private QueueKeys() {}

    /** 주기마다 한 인스턴스만 승격하기 위한 락 */
    public static final String PROMOTION_LOCK = "lock:queue:promote";

    /** 승격 대상 일정 ID 집합 */
    public static final String ACTIVE_SCHEDULES = "queue:active-events";

    /** member=userId, score=진입 순번(INCR) */
    public static String waiting(long scheduleId) {
        return "queue:wait:" + scheduleId;
    }

    /** 입장(활성) 사용자: member=userId, score=세션 만료 시각(ms) */
    public static String active(long scheduleId) {
        return "queue:active:" + scheduleId;
    }

    public static String token(long scheduleId, long userId) {
        return tokenPrefix(scheduleId) + userId;
    }

    public static String tokenPrefix(long scheduleId) {
        return "queue:token:" + scheduleId + ":";
    }

    /** 대기자 생존 신호: member=userId, score=마지막 상태 조회 시각(ms) */
    public static String seen(long scheduleId) {
        return "queue:seen:" + scheduleId;
    }

    public static String sequence(long scheduleId) {
        return "queue:seq:" + scheduleId;
    }

    /** 최근 입장 기록: member="시각:nonce:인원", score=시각(ms), 대기 시간 예측용 */
    public static String admissions(long scheduleId) {
        return "queue:admissions:" + scheduleId;
    }
}
