package com.tailtopia.shop.order.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0：v1.4.2 shop Story 4.1 AC3 —— 按原因的状态 / 库存 / 券 / 退款单（Mockito，不碰 DB）。 */
class ShopOrderRefundServiceTest {

    private final RefundTestRig rig = new RefundTestRig();

    private void assert409(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("缺货整单：PENDING_SHIPMENT → REFUNDING；剩余件回补；行补齐；WHOLE 退款单待选去向；券退回；币段即时退回")
    void outOfStockWhole() {
        var out = rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, "缺货", 7L);

        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        assertThat(rig.order.getCancelReason()).isEqualTo(CancelReason.OUT_OF_STOCK);
        assertThat(rig.order.getCancelledAt()).isEqualTo(RefundTestRig.NOW);
        verify(rig.movements).receiveReturn(eq(11L), eq(2L), eq(RefundTestRig.ORDER_TOKEN), any(), eq(7L));
        verify(rig.movements).receiveReturn(eq(12L), eq(1L), eq(RefundTestRig.ORDER_TOKEN), any(), eq(7L));
        verify(rig.movements).receiveReturn(eq(13L), eq(3L), eq(RefundTestRig.ORDER_TOKEN), any(), eq(7L));
        assertThat(rig.lines).allMatch(l -> l.getRefundedQty() == l.getQty());

        ShopOrderRefund r = rig.only();
        assertThat(out.refund()).isSameAs(r);
        assertThat(r.getScope()).isEqualTo(RefundScope.WHOLE);
        assertThat(r.getTotalRefund()).isEqualTo(329_575L);
        assertThat(r.getCoinRefund()).isEqualTo(100_000L);
        assertThat(r.getCashRefund()).isEqualTo(229_575L);
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.AWAITING_DESTINATION);
        assertThat(r.getCreatedByAdminId()).isEqualTo(7L);
        assertThat(rig.order.getRefundedTotal()).isEqualTo(329_575L);
        assertThat(rig.order.getRefundedCoin()).isEqualTo(100_000L);
        verify(rig.coupons).releaseOnCancel(RefundTestRig.ORDER_ID, CouponReleaseMode.RETURN, RefundTestRig.NOW);
        verify(rig.wallet).credit(RefundTestRig.USER, 100_000L, PawCoinTxnType.REFUND, "SHOP_ORDER",
                RefundTestRig.ORDER_ID, "shopref:" + r.getPublicToken() + ":coin");
        verify(rig.audit).record(eq(7L), eq("SHOP_ORDER_REFUND_HANDLED"), eq("SHOP_ORDER"),
                eq(RefundTestRig.ORDER_TOKEN), anyString());
        verify(rig.notifications).send(eq(RefundTestRig.USER), eq(NotificationType.SHOP_ORDER_EXCEPTION),
                anyString(), anyString(), anyString(), eq(RefundTestRig.ORDER_TOKEN));
    }

    @Test
    @DisplayName("🔴 recordRefund 先于 wallet.credit：退币那一刻订单累计已记账")
    void recordRefundBeforeCredit() {
        AtomicLong refundedAtCredit = new AtomicLong(-1);
        doAnswer(inv -> {
            refundedAtCredit.set(rig.order.getRefundedCoin());
            return null;
        }).when(rig.wallet).credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());

        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, "缺货", 7L);

        assertThat(refundedAtCredit.get()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("运单失败只允许待发货；已发货 → 409 admin.err.orderRefund.statusNotAllowed")
    void waybillFailedOnlyPending() {
        rig.shipped();
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.WAYBILL_FAILED,
                null, 7L)).isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.orderRefund.statusNotAllowed");
                });
        assertThat(rig.store).isEmpty();
    }

    @Test
    @DisplayName("揽收失败：SHIPPED 也可取消（运营已标发货未揽收），回补库存")
    void pickupFailedFromShipped() {
        rig.shipped();
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.PICKUP_FAILED, null, 7L);
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        verify(rig.movements).receiveReturn(eq(11L), eq(2L), anyString(), any(), anyLong());
        assertThat(rig.only().getTotalRefund()).isEqualTo(329_575L);
    }

    @Test
    @DisplayName("快递原因：只允许 SHIPPED；不回补库存；全额退；券退回")
    void courierFault() {
        assert409(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.COURIER_FAULT, null, 7L));
        rig.shipped();
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.COURIER_FAULT, null, 7L);
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        verify(rig.movements, never()).receiveReturn(anyLong(), anyLong(), anyString(), any(), anyLong());
        assertThat(rig.only().getTotalRefund()).isEqualTo(329_575L);
        assertThat(rig.lines).allMatch(l -> l.getRefundedQty() == l.getQty());
        verify(rig.coupons).releaseOnCancel(RefundTestRig.ORDER_ID, CouponReleaseMode.RETURN, RefundTestRig.NOW);
    }

    @Test
    @DisplayName("丢件破损（未保价）：SHIPPED、不回补、金额 = 协商额；缺协商额 422")
    void lostDamagedUninsured() {
        rig.o1(false, 100_000L);
        rig.shipped();
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.LOST_DAMAGED,
                null, null, 7L)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.LOST_DAMAGED, 120_000L, "丢件", 7L);
        ShopOrderRefund r = rig.only();
        assertThat(r.getTotalRefund()).isEqualTo(120_000L);
        assertThat(r.getCoinRefund()).isEqualTo(36_585L);
        verify(rig.movements, never()).receiveReturn(anyLong(), anyLong(), anyString(), any(), anyLong());
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("V1.4.2 shop-admin 5.1（O-3）用户原因退回：先按行入库（库存只加这一次），收齐后取消直接出 WHOLE 退款单、不再回补、券不退")
    void userFaultReturnedInboundThenCancel() {
        rig.shipped();
        rig.order.decideReturnCause(com.tailtopia.shop.logistics.ReturnCause.USER_FAULT);
        // 未入库 → 409
        assert409(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.USER_FAULT_RETURNED, null, 7L));

        var first = rig.service.confirmReturnedInbound(RefundTestRig.ORDER_TOKEN, java.util.Map.of(1L, 1), 7L);
        assertThat(first.complete()).isFalse();
        assertThat(rig.order.getReturnInboundAt()).isNull();
        verify(rig.movements).receiveReturn(eq(11L), eq(1L), anyString(), any(), anyLong());
        // 超过待入库件数 → 409，不回补
        assert409(() -> rig.service.confirmReturnedInbound(RefundTestRig.ORDER_TOKEN, java.util.Map.of(1L, 2), 7L));
        var second = rig.service.confirmReturnedInbound(RefundTestRig.ORDER_TOKEN,
                java.util.Map.of(1L, 1, 2L, 1, 3L, 3), 7L);
        assertThat(second.complete()).isTrue();
        assertThat(rig.order.getReturnInboundAt()).isEqualTo(RefundTestRig.NOW);
        assert409(() -> rig.service.confirmReturnedInbound(RefundTestRig.ORDER_TOKEN, java.util.Map.of(1L, 1), 7L));
        clearInvocations(rig.movements);

        var out = rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.USER_FAULT_RETURNED, null, 7L);
        ShopOrderRefund r = out.refund();
        assertThat(r).isNotNull();
        assertThat(r.getScope()).isEqualTo(RefundScope.WHOLE);
        assertThat(r.getReason()).isEqualTo(CancelReason.USER_FAULT_RETURNED);
        assertThat(r.getTotalRefund()).isEqualTo(315_000L);
        assertThat(r.getShippingRefund()).isZero();
        assertThat(r.getInsuranceRefund()).isZero();
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        verify(rig.movements, never()).receiveReturn(anyLong(), anyLong(), anyString(), any(), anyLong());
        assertThat(rig.lines).allMatch(l -> l.getRefundedQty() == l.getQty());
        verify(rig.coupons).releaseOnCancel(RefundTestRig.ORDER_ID, CouponReleaseMode.KEEP, RefundTestRig.NOW);
    }

    @Test
    @DisplayName("按行入库只认已发货 + return_cause = USER_FAULT；缺货 / 快递原因 409")
    void inboundRequiresUserFault() {
        assert409(() -> rig.service.confirmReturnedInbound(RefundTestRig.ORDER_TOKEN, java.util.Map.of(1L, 1), 7L));
        rig.shipped();
        rig.order.decideReturnCause(com.tailtopia.shop.logistics.ReturnCause.COURIER_FAULT);
        assert409(() -> rig.service.confirmReturnedInbound(RefundTestRig.ORDER_TOKEN, java.util.Map.of(1L, 1), 7L));
        verify(rig.movements, never()).receiveReturn(anyLong(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("S-9 签收后赔付：DELIVERED / COMPLETED；订单状态不变、库存与 refunded_qty 不动；COMPENSATION；券退回")
    void compensateKeepsStatus() {
        rig.completed();
        rig.service.compensate(RefundTestRig.ORDER_TOKEN, null, "破损", 7L);

        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        ShopOrderRefund r = rig.only();
        assertThat(r.getScope()).isEqualTo(RefundScope.COMPENSATION);
        assertThat(r.getReason()).isEqualTo(CancelReason.LOST_DAMAGED);
        assertThat(r.getTotalRefund()).isEqualTo(315_000L);
        assertThat(rig.lines).allMatch(l -> l.getRefundedQty() == 0);
        verify(rig.movements, never()).receiveReturn(anyLong(), anyLong(), anyString(), any(), anyLong());
        verify(rig.coupons).releaseOnCancel(RefundTestRig.ORDER_ID, CouponReleaseMode.RETURN, RefundTestRig.NOW);

        RefundTestRig shipped = new RefundTestRig();
        shipped.shipped();
        assert409(() -> shipped.service.compensate(RefundTestRig.ORDER_TOKEN, null, null, 7L));
    }

    @Test
    @DisplayName("S-9：选去向完成后订单仍 COMPLETED（COMPENSATION 不参与结算）")
    void compensationDoesNotSettleOrder() {
        rig.completed();
        rig.service.compensate(RefundTestRig.ORDER_TOKEN, null, null, 7L);
        rig.service.chooseDestination(RefundTestRig.USER, RefundTestRig.ORDER_TOKEN, rig.only().getPublicToken(),
                new com.tailtopia.shop.order.dto.RefundDestinationRequest("TO_PAWCOIN", null, null, null));
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("部分取消 A×1：订单仍待发货、该行回补 1 件、LINE 退款单 91,380、券不动")
    void cancelLinePartial() {
        rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 1, CancelReason.OUT_OF_STOCK, "缺 1 件", 7L);

        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        verify(rig.movements).receiveReturn(eq(11L), eq(1L), eq(RefundTestRig.ORDER_TOKEN), any(), eq(7L));
        ShopOrderLine a = rig.lines.get(0);
        assertThat(a.getRefundedQty()).isEqualTo(1);
        ShopOrderRefund r = rig.only();
        assertThat(r.getScope()).isEqualTo(RefundScope.LINE);
        assertThat(r.getOrderLineId()).isEqualTo(1L);
        assertThat(r.getLineQty()).isEqualTo(1);
        assertThat(r.getTotalRefund()).isEqualTo(91_380L);
        assertThat(r.getCoinRefund()).isEqualTo(27_726L);
        verify(rig.coupons, never()).releaseOnCancel(anyLong(), any(), any());
        assertThat(rig.order.getRefundedTotal()).isEqualTo(91_380L);
    }

    @Test
    @DisplayName("部分取消到最后一件 → WHOLE 退款单、订单 REFUNDING、券按原因退回；累计 = T、币 = C")
    void cancelLineBecomesWhole() {
        rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 2, CancelReason.OUT_OF_STOCK, null, 7L);
        rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 2L, 1, CancelReason.OUT_OF_STOCK, null, 7L);
        rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 3L, 3, CancelReason.OUT_OF_STOCK, null, 7L);

        assertThat(rig.store).hasSize(3);
        ShopOrderRefund last = rig.store.get(2);
        assertThat(last.getScope()).isEqualTo(RefundScope.WHOLE);
        assertThat(last.getOrderLineId()).isNull();
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        assertThat(rig.order.getRefundedTotal()).isEqualTo(329_575L);
        assertThat(rig.order.getRefundedCoin()).isEqualTo(100_000L);
        verify(rig.coupons).releaseOnCancel(RefundTestRig.ORDER_ID, CouponReleaseMode.RETURN, RefundTestRig.NOW);
    }

    @Test
    @DisplayName("部分取消：非待发货 409、非平台原因 422、行不属于该单 404、超量 409")
    void cancelLineGuards() {
        assertThatThrownBy(() -> rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 1,
                CancelReason.USER_FAULT_RETURNED, null, 7L)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 99L, 1,
                CancelReason.OUT_OF_STOCK, null, 7L)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assert409(() -> rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 3, CancelReason.OUT_OF_STOCK,
                null, 7L));
        rig.shipped();
        assert409(() -> rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 1, CancelReason.OUT_OF_STOCK,
                null, 7L));
        assertThat(rig.store).isEmpty();
    }

    @Test
    @DisplayName("纯币单整单：退款单直接 COMPLETED，订单同一事务内 REFUNDED")
    void pureCoinOrderSettlesImmediately() {
        rig.o1(true, 329_575L);
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        ShopOrderRefund r = rig.only();
        assertThat(r.getCashRefund()).isZero();
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.COMPLETED);
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("纯 QRIS 单：不调钱包")
    void pureQrisNoCredit() {
        rig.o1(true, 0L);
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        assertThat(rig.only().getCoinRefund()).isZero();
        verify(rig.wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("注销防御：已注销用户 → 409，不建单、不退币")
    void deletedUserRefused() {
        rig.userDeleted();
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK,
                null, 7L)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.orderRefund.userDeleted"));
        assertThat(rig.store).isEmpty();
        verify(rig.wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("C-7：平台原因先取消运单；取消失败只告警不阻断；用户原因不调")
    void waybillCancelHook() {
        ShopWaybillCanceller canceller = mock(ShopWaybillCanceller.class);
        when(rig.cancellers.getIfAvailable()).thenReturn(canceller);
        doThrow(new IllegalStateException("not cancellable")).when(canceller).cancelForPlatformCancel(any(), any());

        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);

        verify(canceller).cancelForPlatformCancel(rig.order, 7L);
        verify(rig.alerts).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);

        RefundTestRig other = new RefundTestRig();
        ShopWaybillCanceller c2 = mock(ShopWaybillCanceller.class);
        when(other.cancellers.getIfAvailable()).thenReturn(c2);
        other.userFaultReceived();
        other.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.USER_FAULT_RETURNED, null, 7L);
        verify(c2, never()).cancelForPlatformCancel(any(), any());
    }

    @Test
    @DisplayName("站内信发送失败不回滚处置")
    void notificationFailureDoesNotRollBack() {
        when(rig.notifications.send(anyLong(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("down"));
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        assertThat(rig.store).hasSize(1);
    }

    @Test
    @DisplayName("迟到款退款单：WHOLE / LATE_PAYMENT / 全现金；不记订单累计、不退券、不改状态")
    void latePaymentRefund() {
        ShopOrderRefund r = rig.service.createLatePaymentRefund(rig.order, 229_575L, 7L);
        assertThat(r.getReason()).isEqualTo(CancelReason.LATE_PAYMENT);
        assertThat(r.getCashRefund()).isEqualTo(229_575L);
        assertThat(r.getCoinRefund()).isZero();
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.AWAITING_DESTINATION);
        assertThat(rig.order.getRefundedTotal()).isZero();
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        verify(rig.coupons, never()).releaseOnCancel(anyLong(), any(), any());
    }

    @Test
    @DisplayName("未付款类原因不能走已付款整单取消（422）")
    void unpaidReasonsRejected() {
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.PAYMENT_TIMEOUT,
                null, 7L)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    @DisplayName("复审修复：退款额为 0 → 409，不建单、不退券、不审计、不发站内信、不取消运单")
    void zeroRefundRejectedWithoutSideEffects() {
        ShopWaybillCanceller canceller = mock(ShopWaybillCanceller.class);
        when(rig.cancellers.getIfAvailable()).thenReturn(canceller);
        rig.o1(false, 100_000L);
        rig.shipped();
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.LOST_DAMAGED,
                0L, null, 7L)).isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getType()).isEqualTo(com.tailtopia.shared.error.ErrorTypes.NOTHING_TO_REFUND);
                });
        assertThat(rig.store).isEmpty();
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        verify(rig.coupons, never()).releaseOnCancel(anyLong(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(rig.audit, rig.notifications, rig.wallet, canceller);
    }

    @Test
    @DisplayName("复审修复：校验失败（注销 / 计算器 / 状态）时不调取消运单钩子")
    void hookNotCalledWhenValidationFails() {
        ShopWaybillCanceller canceller = mock(ShopWaybillCanceller.class);
        when(rig.cancellers.getIfAvailable()).thenReturn(canceller);
        rig.o1(false, 100_000L);
        rig.shipped();
        // 计算器：未保价缺协商额
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.LOST_DAMAGED,
                null, null, 7L)).isInstanceOf(AppException.class);
        // 状态：已发货不能缺货取消
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK,
                null, 7L)).isInstanceOf(AppException.class);
        // 注销
        rig.userDeleted();
        assertThatThrownBy(() -> rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.COURIER_FAULT,
                null, 7L)).isInstanceOf(AppException.class);
        verify(canceller, never()).cancelForPlatformCancel(any(), any());

        // 部分取消：超量（计算器 409）不调；转整单才调
        RefundTestRig p = new RefundTestRig();
        ShopWaybillCanceller c2 = mock(ShopWaybillCanceller.class);
        when(p.cancellers.getIfAvailable()).thenReturn(c2);
        assertThatThrownBy(() -> p.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 3, CancelReason.OUT_OF_STOCK,
                null, 7L)).isInstanceOf(AppException.class);
        p.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 1, CancelReason.OUT_OF_STOCK, null, 7L);
        verify(c2, never()).cancelForPlatformCancel(any(), any());
        p.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 1, CancelReason.OUT_OF_STOCK, null, 7L);
        p.service.cancelLine(RefundTestRig.ORDER_TOKEN, 2L, 1, CancelReason.OUT_OF_STOCK, null, 7L);
        p.service.cancelLine(RefundTestRig.ORDER_TOKEN, 3L, 3, CancelReason.OUT_OF_STOCK, null, 7L);
        verify(c2).cancelForPlatformCancel(p.order, 7L);
    }
}
