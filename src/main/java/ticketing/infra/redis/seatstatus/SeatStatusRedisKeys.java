package ticketing.infra.redis.seatstatus;

public final class SeatStatusRedisKeys {
    private SeatStatusRedisKeys() {}

    public static String initDone(long scheduleId) { return "seatStatus:init:" + scheduleId; }
    public static String initLock(long scheduleId) { return "lock:seatStatus:init:" + scheduleId; }

    public static String ver(long scheduleId) { return "seatStatus:ver:" + scheduleId; }
    public static String hash(long scheduleId) { return "seatStatus:hash:" + scheduleId; }
    public static String chg(long scheduleId) { return "seatStatus:chg:" + scheduleId; }
    /** 남은(AVAILABLE) 좌석 수, 초기화 완료 후에만 존재 */
    public static String remaining(long scheduleId) { return "seatStatus:remaining:" + scheduleId; }
}