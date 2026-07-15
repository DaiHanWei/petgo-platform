package com.tailtopia.pay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L0：支付意图状态机守卫（无 DB）。合法迁移放行、非法迁移抛 {@link AppException#conflict}、金额校验。
 */
class PaymentIntentTest {

    private PaymentIntent pending() {
        return PaymentIntent.create(42L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L, "IDR", "tok-abc");
    }

    @Test
    void createStartsPending() {
        PaymentIntent p = pending();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getPublicToken()).isEqualTo("tok-abc");
        assertThat(p.getCurrency()).isEqualTo("IDR");
        assertThat(p.getStatus().isTerminal()).isFalse();
    }

    @Test
    void createRejectsNonPositiveAmount() {
        assertThatThrownBy(() ->
                PaymentIntent.create(1L, PaymentPurpose.AI_UNLOCK, PayChannel.QRIS, 0L, "IDR", "t"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void pendingToPaidThenSecondPaidConflicts() {
        PaymentIntent p = pending();
        p.markPaid(Map.of("k", "v"));
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.getStatus().isTerminal()).isTrue();
        // 二次到账（回调双通道重放）非法迁移 → 冲突（service 层用「已终态即返回」在此之前拦下）
        assertThatThrownBy(() -> p.markPaid(null)).isInstanceOf(AppException.class);
    }

    @Test
    void paidCannotBecomeFailed() {
        PaymentIntent p = pending();
        p.markPaid(null);
        assertThatThrownBy(() -> p.markFailed(null)).isInstanceOf(AppException.class);
    }

    @Test
    void attachGatewayRefOnlyOnPending() {
        PaymentIntent p = pending();
        p.attachGatewayRef("tx-1", Map.of("raw", 1));
        assertThat(p.getGatewayRef()).isEqualTo("tx-1");
        p.markPaid(null);
        assertThatThrownBy(() -> p.attachGatewayRef("tx-2", null)).isInstanceOf(AppException.class);
    }

    @Test
    void pendingToExpiredAndFailedGuarded() {
        assertThat(pendingMarked(PaymentStatus.EXPIRED)).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(pendingMarked(PaymentStatus.FAILED)).isEqualTo(PaymentStatus.FAILED);
    }

    private PaymentStatus pendingMarked(PaymentStatus target) {
        PaymentIntent p = pending();
        switch (target) {
            case EXPIRED -> p.markExpired(null);
            case FAILED -> p.markFailed(null);
            default -> p.markPaid(null);
        }
        return p.getStatus();
    }
}
