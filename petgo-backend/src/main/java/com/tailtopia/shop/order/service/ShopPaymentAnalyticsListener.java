package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
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
 * {@code MilestoneAnalyticsListener} 的既有先例）：{@link AnalyticsClient#capture} 自身已是
 * {@code @Async("analyticsExecutor")}，监听器再叠一层等于往同一个有界池投两次任务
 * （core 1 / max 2 / queue 200 / {@code DiscardPolicy}，见 {@code AsyncConfig}），
 * 只会更早触发丢弃。
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
        analytics.capture(AnalyticsDistinctId.of(e.userId()), "shop_payment_intent_created",
                props(e.amount(), e.payChannel(), e.hasPawcoin()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaid(PaymentIntentPaidEvent e) {
        if (e.purpose() != PaymentPurpose.SHOP_ORDER) {
            return; // 问诊 / 充值 / AI 解锁 / 高清身份证各有各的漏斗，不混进电商口径
        }
        analytics.capture(AnalyticsDistinctId.of(e.userId()), "shop_payment_paid",
                props(e.amount(), e.channel() == null ? null : e.channel().name(), null));
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
                props(e.amount(), e.channel() == null ? null : e.channel().name(), null);
        props.put("failure_category", category.name());
        analytics.capture(AnalyticsDistinctId.of(e.userId()), event, props);
    }

    /**
     * 组装属性。<b>只放这三个键</b>（失败事件另加 {@code failure_category}）。
     *
     * @param hasPawcoin 为 null 时不带该键 —— 支付意图上没有「是否含 PawCoin」这个信息，
     *     瞎填一个 false 会让漏斗按它分组时把混合支付单错归到纯现金一档
     */
    private static Map<String, Object> props(long orderAmount, String payChannel,
            Boolean hasPawcoin) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("order_amount", orderAmount);
        if (payChannel != null) {
            p.put("pay_channel", payChannel);
        }
        if (hasPawcoin != null) {
            p.put("has_pawcoin", hasPawcoin);
        }
        return p;
    }
}
