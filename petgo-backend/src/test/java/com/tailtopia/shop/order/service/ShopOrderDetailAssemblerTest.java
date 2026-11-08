package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.order.service.ShopOrderListFixtures.NOW;
import static com.tailtopia.shop.order.service.ShopOrderListFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import com.tailtopia.shop.coupon.domain.CouponReturnStatus;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.service.ShopLineImageResolver;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（v1.4.2 shop Story 6.2 AC3）：订单详情装配器（Mockito 依赖）。 */
class ShopOrderDetailAssemblerTest {

    private ShopOrderPaymentService payments;
    private ShopOrderFulfillmentService fulfillment;
    private ShopLineImageResolver images;
    private ShopOrderRefundService refunds;
    private LatePaymentService latePayments;
    private CouponLedgerService coupons;
    private ShopOrderDetailAssembler assembler;

    @BeforeEach
    void setUp() {
        payments = mock(ShopOrderPaymentService.class);
        fulfillment = mock(ShopOrderFulfillmentService.class);
        images = mock(ShopLineImageResolver.class);
        refunds = mock(ShopOrderRefundService.class);
        latePayments = mock(LatePaymentService.class);
        coupons = mock(CouponLedgerService.class);
        assembler = new ShopOrderDetailAssembler(payments, fulfillment, images, refunds, latePayments, coupons);
        when(payments.linesOf(any())).thenReturn(List.of());
        when(payments.intentOf(any())).thenReturn(Optional.empty());
        when(fulfillment.shipmentsOf(anyLong())).thenReturn(List.of());
        when(images.mainImageUrlBySkuId(anyCollection())).thenReturn(Map.of());
        when(refunds.detailRefundsOf(any())).thenReturn(ShopOrderRefundService.DetailRefunds.NONE);
        when(latePayments.linksOf(any())).thenReturn(ShopOrderDetailView.ReorderLinks.NONE);
    }

    private static ShopOrderLine line(long id, long orderId, String name, int qty, int refundedQty) {
        ShopOrderLine l = ShopOrderLine.of(orderId, 100L + id, name, "2kg", 50_000L, qty, ReturnPolicy.RETURNABLE);
        ReflectionTestUtils.setField(l, "id", id);
        if (refundedQty > 0) {
            l.addRefundedQty(refundedQty);
        }
        return l;
    }

    @Test
    @DisplayName("存量订单（新列为空）→ 新字段 null / 默认，cancelledLines 为空数组；🔴 每个依赖只调一次（查询数恒定），不碰券账本")
    void stockOrderAndConstantQueries() {
        ShopOrder o = order(1, ShopOrderStatus.COMPLETED, null);
        when(payments.linesOf(o)).thenReturn(List.of(line(11, 1, "A", 1, 0), line(12, 1, "B", 2, 0)));

        ShopOrderDetailView v = assembler.assemble(o, NOW);

        assertThat(v.paidAt()).isNull();
        assertThat(v.cancelledAt()).isNull();
        assertThat(v.cancelReason()).isNull();
        assertThat(v.couponReturn()).isNull();
        assertThat(v.paymentMethods()).isNull();
        assertThat(v.cancelledLines()).isEmpty();
        assertThat(v.amounts().shippingDiscountSource()).isNull();
        assertThat(v.amounts().discountCouponAmount()).isZero();
        assertThat(v.amounts().payable()).isEqualTo(220_000L);
        assertThat(v.lines()).hasSize(2);
        verify(payments, times(1)).linesOf(o);
        verify(payments, times(1)).intentOf(o);
        verify(fulfillment, times(1)).shipmentsOf(1L);
        verify(images, times(1)).mainImageUrlBySkuId(anyCollection());
        verify(refunds, times(1)).detailRefundsOf(o);
        verify(latePayments, times(1)).linksOf(o);
        verifyNoInteractions(coupons);
    }

    @Test
    @DisplayName("部分取消：cancelledLines 的 lineIndex 对应 lines 下标、refundAmount = 该行 LINE 退款合计（无退款单为 0）")
    void cancelledLines() {
        ShopOrder o = order(2, ShopOrderStatus.SHIPPED, null);
        when(payments.linesOf(o)).thenReturn(List.of(line(21, 2, "A", 1, 0), line(22, 2, "B", 3, 2),
                line(23, 2, "C", 1, 1)));
        when(refunds.detailRefundsOf(o)).thenReturn(new ShopOrderRefundService.DetailRefunds(List.of(),
                Map.of(22L, 100_000L)));

        List<ShopOrderDetailView.CancelledLine> cl = assembler.assemble(o, NOW).cancelledLines();

        assertThat(cl).containsExactly(
                new ShopOrderDetailView.CancelledLine(1, "B", "2kg", 2, 100_000L),
                new ShopOrderDetailView.CancelledLine(2, "C", "2kg", 1, 0L));
    }

    @Test
    @DisplayName("已取消订单用过券 → couponReturn 经 CouponLedgerService（订单头两张券 id + 取消原因的退券方式）")
    void couponReturnViaLedger() {
        ShopOrder o = order(3, ShopOrderStatus.CANCELLED, NOW.minusSeconds(600));
        ReflectionTestUtils.setField(o, "cancelReason", CancelReason.PAYMENT_TIMEOUT);
        ReflectionTestUtils.setField(o, "cancelledAt", NOW.minusSeconds(300));
        ReflectionTestUtils.setField(o, "discountUserCouponId", 5L);
        when(coupons.couponReturnStatusOf(eq(3L), anyCollection(), eq(CouponReleaseMode.RETURN), eq(NOW)))
                .thenReturn(CouponReturnStatus.EXPIRED);

        ShopOrderDetailView v = assembler.assemble(o, NOW);

        assertThat(v.couponReturn()).isEqualTo("EXPIRED");
        assertThat(v.cancelReason()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(v.cancelledAt()).isEqualTo(NOW.minusSeconds(300));
        verify(coupons).couponReturnStatusOf(eq(3L), org.mockito.ArgumentMatchers.<Collection<Long>>argThat(
                c -> c.contains(5L)), eq(CouponReleaseMode.RETURN), eq(NOW));
    }

    @Test
    @DisplayName("宽限期待支付（未关单、无取消原因）/ 未用券的已取消单 → couponReturn null，不查券账本")
    void noCouponReturnWhenNotCancelledOrNoCoupon() {
        ShopOrder grace = order(4, ShopOrderStatus.PENDING_PAYMENT, NOW.minusSeconds(60));
        ReflectionTestUtils.setField(grace, "discountUserCouponId", 5L);
        assertThat(assembler.assemble(grace, NOW).couponReturn()).isNull();
        assertThat(assembler.assemble(grace, NOW).displayStatus()).isEqualTo("CANCELLED");

        ShopOrder noCoupon = order(5, ShopOrderStatus.CANCELLED, NOW.minusSeconds(600));
        ReflectionTestUtils.setField(noCoupon, "cancelReason", CancelReason.USER_UNPAID_CANCEL);
        assertThat(assembler.assemble(noCoupon, NOW).couponReturn()).isNull();
        verifyNoInteractions(coupons);
    }
}
