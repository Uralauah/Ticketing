package ticketing.support;

import ticketing.domain.queue.service.QueueService;
import ticketing.global.queue.QueueScheduler;

/** 실제 진입·승격 경로로 입장 상태 생성 */
public final class QueueTestHelper {

    private QueueTestHelper() {}

    /** 대기열 토큰 반환 */
    public static String activate(QueueService queueService,
                                  QueueScheduler queueScheduler,
                                  long scheduleId,
                                  long userId) {
        String token = queueService.enterQueue(scheduleId, userId).token();
        queueScheduler.promoteWaitingUsers();
        return token;
    }
}
