package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shop.order.event.ShopPaymentIntentCreatedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：电商支付漏斗埋点（Story 1-2 AC6/AC7）。
 *
 * <p>🔒 {@link #neverEmitsOrderIdentifiersOrPii()} 是 AC7 最后一条的落点 ——
 * 「写的时候注意点」不算护栏，禁用键必须被逐键断言。
 */
class ShopPaymentAnalyticsListenerTest {

    /** 记录式桩：把每次 capture 的三个实参原样留下，供逐键断言。 */
    private record Captured(String distinctId, String event, Map<String, Object> props) {
    }

    private final List<Captured> captured = new ArrayList<>();

    private final AnalyticsClient recording =
            (distinctId, event, props) -> captured.add(new Captured(distinctId, event, props));

    private final ShopPaymentAnalyticsListener listener =
            new ShopPaymentAnalyticsListener(recording);

    private static final long UID = 42L;

    private PaymentIntentPaidEvent paid(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(1L, "pi-1", UID, purpose, PayChannel.QRIS, 285_000L,
                "IDR");
    }

    private PaymentIntentFailedEvent failed(PaymentPurpose purpose,
            PaymentFailureCategory category) {
        return new PaymentIntentFailedEvent(1L, "pi-1", UID, purpose, PayChannel.QRIS, 285_000L,
                "IDR", category);
    }

    // ---------- 五个事件名（AC6） ----------

    @Test
    @DisplayName("创建意图 → shop_payment_intent_created")
    void intentCreated() {
        listener.onIntentCreated(
                new ShopPaymentIntentCreatedEvent(UID, "MIXED", 285_000L, true));

        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.event()).isEqualTo("shop_payment_intent_created");
            assertThat(c.props()).containsEntry("order_amount", 285_000L)
                    .containsEntry("pay_channel", "MIXED")
                    .containsEntry("has_pawcoin", true);
        });
    }

    @Test
    @DisplayName("到账 → shop_payment_paid")
    void paidEvent() {
        listener.onPaid(paid(PaymentPurpose.SHOP_ORDER));

        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.event()).isEqualTo("shop_payment_paid");
            assertThat(c.props()).containsEntry("order_amount", 285_000L)
                    .containsEntry("pay_channel", "QRIS");
        });
    }

    @Test
    @DisplayName("三类失败各自成事件：declined / expired / user_cancelled")
    void failureCategoriesMapToThreeDistinctEvents() {
        listener.onFailed(failed(PaymentPurpose.SHOP_ORDER,
                PaymentFailureCategory.GATEWAY_DECLINED));
        listener.onFailed(failed(PaymentPurpose.SHOP_ORDER, PaymentFailureCategory.EXPIRED));
        listener.onFailed(failed(PaymentPurpose.SHOP_ORDER,
                PaymentFailureCategory.USER_CANCELLED));

        assertThat(captured).extracting(Captured::event).containsExactly(
                "shop_payment_declined", "shop_payment_expired", "shop_payment_user_cancelled");
        assertThat(captured).extracting(c -> c.props().get("failure_category"))
                .containsExactly("GATEWAY_DECLINED", "EXPIRED", "USER_CANCELLED");
    }

    // ---------- purpose 隔离（AC6 末条） ----------

    @Test
    @DisplayName("🔴 只统计 SHOP_ORDER —— 其余四个 purpose 一条事件都不发")
    void otherPurposesEmitNothing() {
        for (PaymentPurpose p : PaymentPurpose.values()) {
            if (p == PaymentPurpose.SHOP_ORDER) {
                continue;
            }
            listener.onPaid(paid(p));
            listener.onFailed(failed(p, PaymentFailureCategory.GATEWAY_DECLINED));
        }
        assertThat(captured).as("问诊 / 充值 / AI 解锁 / 高清身份证各有各的漏斗").isEmpty();
    }

    @Test
    @DisplayName("failureCategory 为 null → 不发（防御性：归不了类的事件会搅浑三个分支的分母）")
    void nullCategoryEmitsNothing() {
        listener.onFailed(failed(PaymentPurpose.SHOP_ORDER, null));
        assertThat(captured).isEmpty();
    }

    // ---------- AC7：属性集合 ----------

    @Test
    @DisplayName("🔒 逐键断言：订单号与任何 PII 一个都不出现")
    void neverEmitsOrderIdentifiersOrPii() {
        listener.onIntentCreated(new ShopPaymentIntentCreatedEvent(UID, "QRIS", 285_000L, false));
        listener.onPaid(paid(PaymentPurpose.SHOP_ORDER));
        listener.onFailed(failed(PaymentPurpose.SHOP_ORDER,
                PaymentFailureCategory.GATEWAY_DECLINED));

        assertThat(captured).hasSize(3);
        for (Captured c : captured) {
            assertThat(c.props()).doesNotContainKeys(
                    "order_token", "orderToken", "public_token", "publicToken",
                    "display_no", "displayNo", "order_no", "intent_token",
                    "receiver_name", "receiverName", "receiver_phone", "phone",
                    "address", "address_line", "email", "user_id", "userId",
                    "gateway_meta", "gatewayMeta", "gateway_ref", "gatewayRef");
            // 属性键只能取自这四个 —— 多一个都要过 AnalyticsEventGuard 的白名单。
            assertThat(c.props().keySet()).isSubsetOf(
                    "order_amount", "pay_channel", "has_pawcoin", "failure_category");
        }
    }

    @Test
    @DisplayName("distinctId 走 AnalyticsDistinctId.of，不自造、不下发原始 userId")
    void distinctIdIsTheHashedForm() {
        listener.onPaid(paid(PaymentPurpose.SHOP_ORDER));

        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.distinctId()).isEqualTo(AnalyticsDistinctId.of(UID));
            assertThat(c.distinctId()).doesNotContain(String.valueOf(UID));
        });
    }

    @Test
    @DisplayName("支付意图上没有「是否含 PawCoin」的信息 → 到账/失败事件不带该键，而不是瞎填 false")
    void paidAndFailedOmitHasPawcoinRatherThanGuessing() {
        listener.onPaid(paid(PaymentPurpose.SHOP_ORDER));
        listener.onFailed(failed(PaymentPurpose.SHOP_ORDER, PaymentFailureCategory.EXPIRED));

        // 瞎填 false 会让漏斗按它分组时把混合支付单错归到纯现金一档。
        assertThat(captured).allSatisfy(c -> assertThat(c.props()).doesNotContainKey("has_pawcoin"));
    }
}
