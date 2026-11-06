package com.tailtopia.pay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 3.3 实体方法（markPaidAfterClose / markProviderIssued / bindShopOrder / 码失效兜底）。 */
class PaymentIntentLateCloseTest {

    private static PaymentIntent intent(PaymentPurpose purpose) {
        return PaymentIntent.create(7L, purpose, PayChannel.QRIS, 10_000L, "IDR", "tok");
    }

    @Test
    @DisplayName("SHOP_ORDER FAILED → PAID：置 paid_after_close，gateway_ref 为空时补上")
    void failedToPaidAfterClose() {
        PaymentIntent p = intent(PaymentPurpose.SHOP_ORDER);
        p.markFailed(Map.of("reason", "TIMEOUT"));

        p.markPaidAfterClose("gw-1", Map.of("status", "Failure => Success"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.isPaidAfterClose()).isTrue();
        assertThat(p.getGatewayRef()).isEqualTo("gw-1");
        assertThat(p.getGatewayMeta()).containsEntry("status", "Failure => Success");
    }

    @Test
    @DisplayName("SHOP_ORDER EXPIRED → PAID：已有 gateway_ref 不覆盖")
    void expiredToPaidKeepsRef() {
        PaymentIntent p = intent(PaymentPurpose.SHOP_ORDER);
        p.attachGatewayRef("gw-orig", null);
        p.markExpired(null);

        p.markPaidAfterClose("gw-other", null);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.getGatewayRef()).isEqualTo("gw-orig");
    }

    @Test
    @DisplayName("PENDING / PAID 调用即抛；非电商意图调用即抛")
    void rejectsWrongStateOrPurpose() {
        PaymentIntent pending = intent(PaymentPurpose.SHOP_ORDER);
        assertThatThrownBy(() -> pending.markPaidAfterClose("gw", null)).isInstanceOf(IllegalStateException.class);

        PaymentIntent paid = intent(PaymentPurpose.SHOP_ORDER);
        paid.markPaid(null);
        assertThatThrownBy(() -> paid.markPaidAfterClose("gw", null)).isInstanceOf(IllegalStateException.class);

        PaymentIntent gift = intent(PaymentPurpose.GIFT);
        gift.markExpired(null);
        assertThatThrownBy(() -> gift.markPaidAfterClose("gw", null)).isInstanceOf(IllegalStateException.class);
        assertThat(gift.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(gift.isPaidAfterClose()).isFalse();
    }

    @Test
    @DisplayName("markProviderIssued 同步写 provider_expires_at 与 expires_at；仅 PENDING")
    void markProviderIssuedSyncsBoth() {
        PaymentIntent p = PaymentIntent.create(7L, PaymentPurpose.SHOP_ORDER, PayChannel.QRIS, 10_000L, "IDR", "t",
                Instant.parse("2026-11-05T10:00:00Z"));
        Instant at = Instant.parse("2026-11-05T11:10:00Z");

        p.markProviderIssued(at);

        assertThat(p.getProviderExpiresAt()).isEqualTo(at);
        assertThat(p.getExpiresAt()).isEqualTo(at);
        p.markFailed(null);
        assertThatThrownBy(() -> p.markProviderIssued(at)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("effectiveProviderExpiry：有 provider_expires_at 用它；否则 created_at + 65 分钟（存量兜底）")
    void effectiveProviderExpiryFallsBackToCreatedPlus65() {
        PaymentIntent legacy = intent(PaymentPurpose.SHOP_ORDER);
        Instant created = Instant.parse("2026-11-05T10:00:00Z");
        ReflectionTestUtils.setField(legacy, "createdAt", created);
        assertThat(legacy.effectiveProviderExpiry()).isEqualTo(created.plus(Duration.ofMinutes(65)));

        legacy.markProviderIssued(Instant.parse("2026-11-05T11:20:00Z"));
        assertThat(legacy.effectiveProviderExpiry()).isEqualTo(Instant.parse("2026-11-05T11:20:00Z"));
    }

    @Test
    @DisplayName("bindShopOrder 仅当为空时写；非电商意图即抛")
    void bindShopOrderOnlyOnce() {
        PaymentIntent p = intent(PaymentPurpose.SHOP_ORDER);
        p.bindShopOrder(5L);
        p.bindShopOrder(6L);
        assertThat(p.getShopOrderId()).isEqualTo(5L);
        assertThatThrownBy(() -> intent(PaymentPurpose.PAWCOIN_TOPUP).bindShopOrder(5L))
                .isInstanceOf(IllegalStateException.class);
    }
}
