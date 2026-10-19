package com.tailtopia.profile.slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 到账发放后的宠物状态 reconcile（V1.4.0 multi-pet Story 2.2 · code review #1）：<b>AFTER_COMMIT + REQUIRES_NEW</b>。
 *
 * <p>reconcile 移出 {@code applyCallback} 的事务：它若抛错，只回滚自己这个新事务，意图 PAID 与名额已经提交。
 * {@code RENTAL_ADDED} / {@code RENEWED} 只恢复不降级，延后执行不会让任何宠物错误变只读；失败只记日志（userId / 触发），
 * 下一次触发（续租 / 删宠物 / 等级变化 / 改选）会再算一遍。
 *
 * <p>afterCommit 阶段事务资源仍绑定在线程上，必须显式 REQUIRES_NEW（Spring 文档）；用 {@link TransactionTemplate} 而不在
 * 方法上挂 {@code @Transactional}，异常才能在这里被吞掉、不冒泡给已提交事务的调用方。
 */
@Component
public class PetSlotGrantedReconcileListener {

    private static final Logger log = LoggerFactory.getLogger(PetSlotGrantedReconcileListener.class);

    private final PetSlotStateService state;
    private final TransactionOperations newTx;

    @Autowired
    public PetSlotGrantedReconcileListener(PetSlotStateService state, PlatformTransactionManager txManager) {
        this(state, requiresNew(txManager));
    }

    PetSlotGrantedReconcileListener(PetSlotStateService state, TransactionOperations newTx) {
        this.state = state;
        this.newTx = newTx;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager txManager) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGranted(PetSlotGrantedEvent e) {
        try {
            newTx.executeWithoutResult(status -> state.reconcile(e.userId(), e.trigger()));
        } catch (RuntimeException ex) {
            log.error("月租发放后宠物状态 reconcile 失败 userId={} trigger={} err={}", e.userId(), e.trigger(),
                    ex.getClass().getSimpleName());
        }
    }
}
