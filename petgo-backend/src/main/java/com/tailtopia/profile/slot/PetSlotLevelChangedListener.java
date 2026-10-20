package com.tailtopia.profile.slot;

import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 等级变化 → 重算宠物状态（V1.4.0 multi-pet Story 2.1 · AD-4 / AC9）。
 *
 * <p>只监听地基事件 {@link GrowthLevelChangedEvent}（只引用、不改字段）。升 / 降都调
 * {@code reconcile(userId, LEVEL_CHANGED)}：升级名额增加 → 只读宠物按序恢复；降级 → {@code LEVEL_CHANGED} 只恢复不降级，
 * 不转任何宠物为只读。事务由 {@link PetSlotStateService#reconcile} 自己开（锁 MANDATORY 挂在它上面）；
 * 本方法不加 {@code @Transactional}（Spring 6.1+ 禁止 {@code @TransactionalEventListener} 上挂默认传播的事务）。
 * AFTER_COMMIT + {@code @Async}：异常只记日志、不冒泡。
 */
@Component
public class PetSlotLevelChangedListener {

    private static final Logger log = LoggerFactory.getLogger(PetSlotLevelChangedListener.class);

    private final PetSlotStateService state;

    public PetSlotLevelChangedListener(PetSlotStateService state) {
        this.state = state;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLevelChanged(GrowthLevelChangedEvent e) {
        try {
            state.reconcile(e.userId(), PetSlotTrigger.LEVEL_CHANGED);
        } catch (RuntimeException ex) {
            log.warn("等级变化后宠物状态 reconcile 失败 userId={} err={}", e.userId(), ex.getClass().getSimpleName());
        }
    }
}
