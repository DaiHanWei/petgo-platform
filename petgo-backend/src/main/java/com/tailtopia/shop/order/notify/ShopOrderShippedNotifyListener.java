package com.tailtopia.shop.order.notify;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shop.order.event.ShopOrderShippedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Biteship 揽收 / 运营「标记已发货」后给用户发「订单已发货」（v1.4.2 shop Story 5.1 AC8.4）。
 *
 * <p>🔴 写库监听纪律：{@code AFTER_COMMIT} + {@code REQUIRES_NEW} —— 推进事务回滚则不发。文案与参数照
 * {@code AdminShopOrderService#notifyShipped}（不带商品名 / 收件人 / 地址；{@code targetRef} = 订单 token）。
 * 发送失败只 WARN。不新增通知类型。
 */
@Component
public class ShopOrderShippedNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderShippedNotifyListener.class);

    private final NotificationService notifications;

    public ShopOrderShippedNotifyListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onShipped(ShopOrderShippedEvent e) {
        try {
            notifications.send(e.userId(), NotificationType.SHOP_ORDER_SHIPPED,
                    "订单已发货", "包裹已寄出，点击查看物流单号",
                    NotificationType.SHOP_ORDER_SHIPPED.name(), e.orderToken());
        } catch (RuntimeException ex) {
            log.warn("发货通知发送失败（不影响发货）token={} cause={}", e.orderToken(),
                    ex.getClass().getSimpleName());
        }
    }
}
