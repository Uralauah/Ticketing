package ticketing.infra.redis.seatstatus;

public final class SeatStatusTopic {
    private SeatStatusTopic() {}

    public static final String PREFIX = "seatStatus:topic:";
    public static final String PATTERN = "seatStatus:topic:*";

    public static String channel(long scheduleId) {
        return PREFIX + scheduleId;
    }

    public static Long parseScheduleId(String channel) {
        if (channel == null || !channel.startsWith(PREFIX)) return null;
        try {
            return Long.parseLong(channel.substring(PREFIX.length()));
        } catch (Exception e) {
            return null;
        }
    }
}