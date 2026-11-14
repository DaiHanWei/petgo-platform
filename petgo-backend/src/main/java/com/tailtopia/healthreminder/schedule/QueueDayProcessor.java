package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.service.LaunchQueueService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ⑧ 一个用户的出队 + 投递 + 记发送日在同一个事务、同一组宠物锁里（V1.5.0 Story 2.3）：选中的行不会在投递前被并发重建改掉；
 * 投递成功与「最近一次排队发送日」一起提交。
 */
@Service
public class QueueDayProcessor {

    private final LaunchQueueService queue;
    private final HealthDispatchPort dispatcher;

    public QueueDayProcessor(LaunchQueueService queue, HealthDispatchPort dispatcher) {
        this.queue = queue;
        this.dispatcher = dispatcher;
    }

    /** @return 是否发出了一条排队建议 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean process(long userId, LocalDate day, boolean userBusyToday, boolean queuePaused) {
        Optional<HealthReminder> picked = queue.pickForUser(userId, day, userBusyToday, queuePaused);
        if (picked.isEmpty()) {
            return false;
        }
        HealthReminder row = picked.get();
        boolean sent = dispatcher.dispatch(new SendGroup(row.getPetProfileId(), userId, day, PushPoint.NONE,
                List.of(row))).sent();
        if (sent) {
            queue.markQueueSent(userId, day);
        }
        return sent;
    }
}
