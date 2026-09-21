package com.tailtopia.pay.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：{@link PaymentFailureCategory#of(PaymentIntent)} 的映射表逐行钉死（Story 1-1 AC1/AC6）。
 *
 * <p>🔴 {@link #expiredStatusIsExpiredNotGatewayDeclined()} 是本类的核心：判序一旦写成
 * 「先 reason 后 status」，超时就会被稳定误标成网关拒付，App 随之给出一个不该给的重试入口。
 * 该用例变红即说明判序写反了，<b>不许改断言迁就实现</b>。
 */
class PaymentFailureCategoryTest {

    private PaymentIntent pending() {
        return PaymentIntent.create(42L, PaymentPurpose.SHOP_ORDER, PayChannel.QRIS, 10000L, "IDR",
                "tok-pfc");
    }

    private PaymentIntent failedWith(Map<String, Object> meta) {
        PaymentIntent p = pending();
        p.markFailed(meta);
        return p;
    }

    // ---------- 映射表 7 行 ----------

    @Test
    @DisplayName("① intent 为 null → null")
    void nullIntentIsNull() {
        assertThat(PaymentFailureCategory.of(null)).isNull();
    }

    @Test
    @DisplayName("② PENDING / PAID → null（还没失败，无类别可言）")
    void pendingAndPaidAreNull() {
        assertThat(PaymentFailureCategory.of(pending())).isNull();

        PaymentIntent paid = pending();
        paid.markPaid(Map.of("gatewayRef", "g-1"));
        assertThat(PaymentFailureCategory.of(paid)).isNull();
    }

    @Test
    @DisplayName("③ status=EXPIRED → EXPIRED，且不看 meta（四个置 EXPIRED 的点 meta 全传 null）")
    void expiredStatusIsExpiredNotGatewayDeclined() {
        PaymentIntent expiredNoMeta = pending();
        expiredNoMeta.markExpired(null);
        assertThat(PaymentFailureCategory.of(expiredNoMeta))
                .isEqualTo(PaymentFailureCategory.EXPIRED);

        // 扫描器置 EXPIRED 时 gateway_meta 保持下单时的 charge 快照（无 reason 键）——仍须是 EXPIRED。
        PaymentIntent expiredWithChargeSnapshot = pending();
        expiredWithChargeSnapshot.markExpired(Map.of("qrString", "00020101", "gatewayRef", "g-2"));
        assertThat(PaymentFailureCategory.of(expiredWithChargeSnapshot))
                .isEqualTo(PaymentFailureCategory.EXPIRED);
    }

    @Test
    @DisplayName("④ FAILED 且 gatewayMeta 为 null → GATEWAY_DECLINED")
    void failedWithNullMetaIsGatewayDeclined() {
        assertThat(PaymentFailureCategory.of(failedWith(null)))
                .isEqualTo(PaymentFailureCategory.GATEWAY_DECLINED);
    }

    @Test
    @DisplayName("⑤ FAILED 且 reason=TIMEOUT → EXPIRED")
    void failedWithTimeoutReasonIsExpired() {
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("reason", "TIMEOUT"))))
                .isEqualTo(PaymentFailureCategory.EXPIRED);
    }

    @Test
    @DisplayName("⑥ FAILED 且 reason ∈ {USER_CANCEL, CANCELLED} → USER_CANCELLED")
    void failedWithUserCancelReasonIsUserCancelled() {
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("reason", "USER_CANCEL"))))
                .isEqualTo(PaymentFailureCategory.USER_CANCELLED);
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("reason", "CANCELLED"))))
                .isEqualTo(PaymentFailureCategory.USER_CANCELLED);
    }

    @Test
    @DisplayName("⑦ FAILED 且 reason 缺键 / 为未知值 → GATEWAY_DECLINED")
    void failedWithMissingOrUnknownReasonIsGatewayDeclined() {
        // 网关回调 FAILED 的常态：rawMeta 原文没有 reason 键。
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("status", "DECLINED", "code", "51"))))
                .isEqualTo(PaymentFailureCategory.GATEWAY_DECLINED);
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("reason", "INSUFFICIENT_FUNDS"))))
                .isEqualTo(PaymentFailureCategory.GATEWAY_DECLINED);
    }

    // ---------- 边界：模糊匹配防线 ----------

    @Test
    @DisplayName("reason 逐字面量区分大小写匹配 —— 不做 contains / 忽略大小写的模糊匹配")
    void reasonMatchIsExactAndCaseSensitive() {
        // 若实现用 equalsIgnoreCase 或 contains，下面三条都会被误判成 USER_CANCELLED / EXPIRED。
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("reason", "user_cancel"))))
                .isEqualTo(PaymentFailureCategory.GATEWAY_DECLINED);
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("reason", "CARD_CANCELLED_BY_BANK"))))
                .isEqualTo(PaymentFailureCategory.GATEWAY_DECLINED);
        assertThat(PaymentFailureCategory.of(failedWith(Map.of("reason", "TIMEOUT_RETRYABLE"))))
                .isEqualTo(PaymentFailureCategory.GATEWAY_DECLINED);
    }

    @Test
    @DisplayName("枚举只有三值，且线上表示为 UPPER_SNAKE 字面量")
    void enumValueSetIsPinned() {
        assertThat(PaymentFailureCategory.values())
                .containsExactly(PaymentFailureCategory.GATEWAY_DECLINED,
                        PaymentFailureCategory.EXPIRED, PaymentFailureCategory.USER_CANCELLED);
        assertThat(PaymentFailureCategory.GATEWAY_DECLINED.name()).isEqualTo("GATEWAY_DECLINED");
    }
}
