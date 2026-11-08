package com.tailtopia.shop.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ReturnPolicy;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.4.2 shop-admin Story 6.2 AC3 / AC4）：用户原因退回按行入库 —— 可分次、累计不超应收（qty − refunded_qty），
 * 短收时仍有待入库件（不放行退款）。
 */
class ReturnInboundQtyTest {

    private static ShopOrderLine line(int qty) {
        return ShopOrderLine.of(1L, 2L, "Royal Canin", "2kg", 100_000L, qty, ReturnPolicy.RETURNABLE);
    }

    @Test
    void inboundCanBeSplitAcrossSeveralConfirmations() {
        ShopOrderLine l = line(3);
        l.addReturnInboundQty(1);
        assertThat(l.pendingInboundQty()).as("短收：还差 2 件").isEqualTo(2);
        l.addReturnInboundQty(2);
        assertThat(l.getReturnInboundQty()).isEqualTo(3);
        assertThat(l.pendingInboundQty()).isZero();
    }

    @Test
    void cumulativeInboundCannotExceedExpected() {
        ShopOrderLine l = line(2);
        l.addReturnInboundQty(2);
        assertThatThrownBy(() -> l.addReturnInboundQty(1)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.v142.inbound.qtyExceeded");
    }

    @Test
    void alreadyRefundedPiecesAreNotExpectedBack() {
        ShopOrderLine l = line(3);
        l.addRefundedQty(1);
        assertThat(l.pendingInboundQty()).isEqualTo(2);
        assertThatThrownBy(() -> l.addReturnInboundQty(3)).isInstanceOf(AppException.class);
        l.addReturnInboundQty(2);
        assertThat(l.pendingInboundQty()).isZero();
    }

    @Test
    void zeroOrNegativeQtyIsRejected() {
        ShopOrderLine l = line(1);
        assertThatThrownBy(() -> l.addReturnInboundQty(0)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.v142.inbound.qtyPositive");
        assertThatThrownBy(() -> l.addReturnInboundQty(-1)).isInstanceOf(AppException.class);
        assertThat(l.getReturnInboundQty()).isZero();
    }
}
