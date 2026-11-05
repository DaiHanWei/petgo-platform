package com.tailtopia.shop.logistics;

import com.tailtopia.shop.logistics.event.ShopWaybillCancelRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 平台原因取消提交后取消 Biteship 运单（v1.4.2 shop Story 5.1，4.1 钩子的落地；C-7）。
 *
 * <p>🔴 {@code AFTER_COMMIT} + {@code @Async}：取消事务回滚则不取消运单；Biteship 调用在独立线程、不在任何事务内。
 * 方法本身不开事务（{@link BiteshipShipmentService} 内部用短事务落库）。{@code fallbackExecution}：无事务时直接执行。
 */
@Component
public class BiteshipPlatformCancelListener {

    private static final Logger log = LoggerFactory.getLogger(BiteshipPlatformCancelListener.class);

    private final BiteshipShipmentService shipments;

    public BiteshipPlatformCancelListener(BiteshipShipmentService shipments) {
        this.shipments = shipments;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCancelRequested(ShopWaybillCancelRequestedEvent e) {
        try {
            shipments.cancelAfterPlatformCancel(e);
        } catch (RuntimeException ex) {
            log.warn("平台原因取消后的运单取消处理失败 order={} cause={}", e.orderDisplayNo(),
                    ex.getClass().getSimpleName());
        }
    }
}
