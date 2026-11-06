package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.dto.ShopOrderRefundView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.4.2 shop-admin Story 7.1 · AD-12 · O-30）：预计待扣回 = 全部未完成现金段（待选择 / 待打款 / 打款失败）按扣回同规则
 * （HALF_UP，分母订单实付）估算；已完成段、LATE_PAYMENT、无现金段不计；合计不超过「入账 − 已扣回」。
 */
class AfterSalesPendingReverseTest {

    private static ShopOrderRefundView v(String reason, String status, long coin, long cash) {
        return new ShopOrderRefundView("rf", "WHOLE", reason, status, coin + cash, coin, cash, 0, 0, "BCA", "1234",
                false, false, null, Instant.EPOCH, "TO_BANK", null, false, 0);
    }

    @Test
    void uiExampleCashSegmentAwaitingPayout() {
        // F7 稿：实付 146.500，入账 146，PawCoin 段 50.000 已退（扣回 50），现金段 96.500 待打款 → 预计待扣回 96
        long pending = AdminShopOrderAfterSalesService.pendingReverse(146, 50,
                List.of(v("OUT_OF_STOCK", "PAYOUT_PENDING", 50_000, 96_500)), 146_500);
        assertThat(pending).isEqualTo(96);
    }

    @Test
    void allUnfinishedStatusesCountButCompletedAndLatePaymentDoNot() {
        var views = List.of(v("OUT_OF_STOCK", "AWAITING_DESTINATION", 0, 10_000),
                v("OUT_OF_STOCK", "PAYOUT_FAILED", 0, 10_000),
                v("OUT_OF_STOCK", "COMPLETED", 0, 10_000),
                v("LATE_PAYMENT", "PAYOUT_PENDING", 0, 10_000));
        // 100 × 10.000 / 100.000 = 10，两段计入
        assertThat(AdminShopOrderAfterSalesService.pendingReverse(100, 0, views, 100_000)).isEqualTo(20);
    }

    @Test
    void cappedAtAccruedMinusReversedAndZeroWhenNoBase() {
        var views = List.of(v("OUT_OF_STOCK", "PAYOUT_PENDING", 0, 100_000));
        assertThat(AdminShopOrderAfterSalesService.pendingReverse(100, 95, views, 100_000)).isEqualTo(5);
        assertThat(AdminShopOrderAfterSalesService.pendingReverse(100, 0, views, 0)).isZero();
        assertThat(AdminShopOrderAfterSalesService.pendingReverse(0, 0, views, 100_000)).isZero();
    }

    @Test
    void halfUpLikeTheRealReversal() {
        // 7 × 15.000 / 20.000 = 5.25 → 5；7 × 25.000 / 20.000 > 7 → 封顶 7（同 GrowthRefundListener.proportional）
        assertThat(AdminShopOrderAfterSalesService.pendingReverse(7, 0,
                List.of(v("OUT_OF_STOCK", "PAYOUT_PENDING", 0, 15_000)), 20_000)).isEqualTo(5);
    }

    @Test
    void compensationAwaitingChoiceIsNotEstimated() {
        var comp = new ShopOrderRefundView("rf", "COMPENSATION", "LOST_DAMAGED", "AWAITING_DESTINATION", 50_000, 0,
                50_000, 0, 0, null, null, true, false, null, Instant.EPOCH, null, null, true, 0);
        assertThat(AdminShopOrderAfterSalesService.pendingReverse(100, 0, List.of(comp), 100_000)).isZero();
    }
}
