package ticketing.global.queue;

public interface QueueProperties {
    String WAIT_QUEUE_KEY = "queue:wait:%s";
    String ACTIVE_QUEUE_KEY = "queue:active:%s";
    String ACTIVE_EVENTS_KEY = "queue:active-events";
    String TOKEN_KEY = "queue:token:%s:%s";
    long MAX_ACTIVE_USERS = 1000;
}
