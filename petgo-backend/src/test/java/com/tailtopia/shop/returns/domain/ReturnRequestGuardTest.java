package com.tailtopia.shop.returns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import org.junit.jupiter.api.Test;

/** 0904 复审修复：退货申请状态守卫（去向不可改 / 重复批准 / 质检中不可普通驳回）。 */
class ReturnRequestGuardTest {

    private static ReturnRequest pending(ReturnType type) {
        return ReturnRequest.open("rt_1", 1L, 7L, type, true, "note", null,
                ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    void cashDestinationCannotChangeAfterRefundExecuted() {
        ReturnRequest r = pending(ReturnType.CANCEL_BEFORE_SHIPMENT);
        r.chooseCashDestination(CashDestination.TO_PAWCOIN, null, null, null);
        r.approve(1L);
        r.markRefunded();
        assertThatThrownBy(() -> r.chooseCashDestination(CashDestination.TO_BANK, PayoutChannel.BCA,
                "123", "A"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不可更改");
        assertThat(r.getCashDestination()).isEqualTo(CashDestination.TO_PAWCOIN);
    }

    @Test
    void cashDestinationChangeableWhileActive() {
        ReturnRequest r = pending(ReturnType.QUALITY_ISSUE);
        r.chooseCashDestination(CashDestination.TO_PAWCOIN, null, null, null);
        r.approve(1L);
        r.chooseCashDestination(CashDestination.TO_BANK, PayoutChannel.BCA, "123", "A");
        assertThat(r.getCashDestination()).isEqualTo(CashDestination.TO_BANK);
    }

    @Test
    void approveTwiceIsRejectedAndDeadlineNotExtended() {
        ReturnRequest r = pending(ReturnType.QUALITY_ISSUE);
        r.approve(1L);
        var deadline = r.getShipbackDeadline();
        assertThatThrownBy(() -> r.approve(2L)).isInstanceOf(AppException.class);
        assertThat(r.getShipbackDeadline()).isEqualTo(deadline);
    }

    @Test
    void rejectNotAllowedWhileInspecting() {
        ReturnRequest r = pending(ReturnType.QUALITY_ISSUE);
        r.approve(1L);
        r.transitionTo(ReturnStatus.INSPECTING);
        assertThatThrownBy(() -> r.reject(1L, "reason")).isInstanceOf(AppException.class);
        assertThat(r.getStatus()).isEqualTo(ReturnStatus.INSPECTING);
    }

    @Test
    void rejectAllowedFromPendingReview() {
        ReturnRequest r = pending(ReturnType.QUALITY_ISSUE);
        r.reject(1L, "reason");
        assertThat(r.getStatus()).isEqualTo(ReturnStatus.REJECTED);
    }
}
