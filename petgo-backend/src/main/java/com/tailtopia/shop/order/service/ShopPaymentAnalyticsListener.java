package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shop.order.event.ShopOrderPaidEvent;
import com.tailtopia.shop.order.event.ShopPaymentIntentCreatedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 电商支付漏斗的服务端埋点（Story 1-2 · SHOP-FR-02 · SD-1）。
 *
 * <p>五个事件对应支付的五个结局：创建意图 → 到账 / 网关拒付 / 超时 / 用户取消。
 * 客户端埋点会被广告拦截与丢包吃掉，服务端这一份是权威分母。
 *
 * <p>🔴 <b>刻意不加 {@code @Async}</b>（与 epics 1-2 AC3 的有意偏离，照
 * {@code MilestoneAnalyticsListener} 的既有先例）：{@link AnalyticsClient#captureForUser} 只是内存非阻塞入队
 * （analytics-pipeline Story 1.9 起写自建埋点接收队列，满了丢弃计数），调用立刻返回、不抛异常，
 * 无需再叠一层异步。
 *
 * <p>🔴 <b>本监听器不写库</b>，所以不涉及 {@code REQUIRES_NEW}。若后续有人想在这里顺手落库，
 * 必须先回看 {@code notify/service/NotificationService} 的 AFTER_COMMIT 吞写事故。
 *
 * <p>🔒 <b>属性只有枚举与数值</b>：订单号、收件人、电话、地址、邮箱、{@code gateway_meta}
 * 一个片段都不进（SHOP-NFR-01）。{@code AnalyticsEventGuard} 的属性白名单是代码层的第二道闸。
 */
@Component
public class ShopPaymentAnalyticsListener {

    private final AnalyticsClient analytics;

    public ShopPaymentAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIntentCreated(ShopPaymentIntentCreatedEvent e) {
        analytics.captureForUser(e.userId(), "shop_payment_intent_created",
                props(e.payChannel(), e.hasPawcoin()));
    }

    /**
     * 到账 → {@code shop_payment_paid}。
     *
     * <p>🔴 v1.4.2 shop Story 3.2（D-23）：由 {@code PaymentIntentPaidEvent} 挪到 {@link ShopOrderPaidEvent} ——
     * 一处发，网关 / 纯币 / 零元单 / 4.2 重下单都覆盖（纯币单此前缺这条）；3.3 迟到款意图虽发意图事件，
     * 但不经「已付款」迁移，不会误发。属性键集合不变。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPaid(ShopOrderPaidEvent e) {
        analytics.captureForUser(e.userId(), "shop_payment_paid",
                props(e.payChannel(), null));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFailed(PaymentIntentFailedEvent e) {
        if (e.purpose() != PaymentPurpose.SHOP_ORDER) {
            return;
        }
        PaymentFailureCategory category = e.failureCategory();
        if (category == null) {
            // 防御性：失败事件按定义总有类别，真出现 null 说明上游算错了 —— 宁可不发，
            // 也不要发一条归不了类的事件把三个分支的分母都搅浑。
            return;
        }
        String event = switch (category) {
            case GATEWAY_DECLINED -> "shop_payment_declined";
            case EXPIRED -> "shop_payment_expired";
            case USER_CANCELLED -> "shop_payment_user_cancelled";
        };
        Map<String, Object> props =
                props(e.channel() == null ? null : e.channel().name(), null);
        props.put("failure_category", category.name());
        analytics.captureForUser(e.userId(), event, props);
    }

    /**
     * 组装属性。<b>只放这两个键</b>（pay_channel / has_pawcoin）（失败事件另加 {@code failure_category}）。
     *
     * @param hasPawcoin 为 null 时不带该键 —— 支付意图上没有「是否含 PawCoin」这个信息，
     *     瞎填一个 false 会让漏斗按它分组时把混合支付单错归到纯现金一档
     */
    private static Map<String, Object> props(String payChannel, Boolean hasPawcoin) {
        Map<String, Object> p = new LinkedHashMap<>();
        // V1.4.2：不再带 order_amount —— 金额一律不进 PostHog（2026-11-08 拍板）。
        if (payChannel != null) {
            p.put("pay_channel", payChannel);
        }
        if (hasPawcoin != null) {
            p.put("has_pawcoin", hasPawcoin);
        }
        return p;
    }
}
