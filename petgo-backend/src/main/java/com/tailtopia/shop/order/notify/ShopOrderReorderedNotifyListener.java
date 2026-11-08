package com.tailtopia.shop.order.notify;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shop.order.event.ShopOrderReorderedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 重新下单后给用户发通知（v1.4.2 shop Story 4.2 AC5，D-21、PRD §6 F22、FR-134.2）。
 *
 * <p>🔴 写库监听纪律：{@code AFTER_COMMIT} + {@code REQUIRES_NEW} —— 重下单事务回滚则不发，不留孤儿通知。
 * 推送文案按收件人语言取 {@code notify.SHOP_ORDER_REORDERED.title/body}（{0} = 新单展示号）；{@code targetRef} = 新单 token，
 * 推送分类 {@code ORDER_ACCOUNT}（2.3 映射，不可关）。发送失败只 WARN，不影响已提交的重下单。
 */
@Component
public class ShopOrderReorderedNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderReorderedNotifyListener.class);

    static final String COPY_KEY = "SHOP_ORDER_REORDERED";
    static final String FALLBACK_TITLE = "Pesananmu sudah dibuat ulang";
    static final String FALLBACK_BODY = "Pembayaranmu sudah kami terima. Pesanan %s sedang disiapkan.";

    private final NotificationService notifications;

    public ShopOrderReorderedNotifyListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReordered(ShopOrderReorderedEvent e) {
        try {
            notifications.sendWithCopy(e.userId(), NotificationType.SHOP_ORDER_REORDERED, COPY_KEY,
                    new Object[] {e.newOrderDisplayNo()}, FALLBACK_TITLE,
                    FALLBACK_BODY.formatted(e.newOrderDisplayNo()), e.newOrderToken());
        } catch (RuntimeException ex) {
            log.warn("重新下单通知发送失败 newOrderToken={} error={}", e.newOrderToken(),
                    ex.getClass().getSimpleName());
        }
    }
}
