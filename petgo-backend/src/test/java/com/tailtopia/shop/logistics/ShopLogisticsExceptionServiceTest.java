package com.tailtopia.shop.logistics;

import static com.tailtopia.shop.logistics.LogisticsFixtures.NOW;
import static com.tailtopia.shop.logistics.LogisticsFixtures.ORDER_ID;
import static com.tailtopia.shop.logistics.LogisticsFixtures.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/** L0：v1.4.2 shop Story 5.2 AC7 / AC8 —— 运营处置：揽收失败三种处置、退回确认、丢件破损、清除、索赔提醒、列表。 */
class ShopLogisticsExceptionServiceTest {

    private static final Long ACTOR = 42L;
    private static final Instant AT = NOW.plusSeconds(86_400);

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final BiteshipShipmentService waybills = mock(BiteshipShipmentService.class);
    private final ShopOrderFulfillmentService fulfillment = mock(ShopOrderFulfillmentService.class);
    private final ShopOrderRefundService refunds = mock(ShopOrderRefundService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);

    private ShopLogisticsExceptionService service;
    private ShopOrder order;

    @BeforeEach
    void setUp() {
        service = new ShopLogisticsExceptionService(orders, shipments, waybills, fulfillment, refunds, audit, alerts,
                txm, Clock.fixed(AT, ZoneOffset.UTC));
        order = LogisticsFixtures.paidOrder(false);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private void pickupFailed() {
        order.flagLogisticsException(LogisticsException.PICKUP_FAILED, NOW);
    }

    /** retryWaybill 建成 → 之后查活动运单能看到新的 Biteship 运单。 */
    private void retryCreatesNewWaybill() {
        when(waybills.retryWaybill(ORDER_ID, ACTOR)).thenAnswer(inv -> {
            when(shipments.findActiveByShopOrderId(ORDER_ID))
                    .thenReturn(List.of(LogisticsFixtures.biteshipShipment(2L, "bs_new")));
            return WaybillResult.ok();
        });
    }

    // ===================== 复审 1 / 5：并发交错与前置 =====================

    @Test
    @DisplayName("复审 1：重下运单进行中被「取消订单」完成（订单 REFUNDING）→ 收尾不清标记、不审计、告警（展示号）、返回中止")
    void retryInterleavedWithCancel() {
        pickupFailed();
        when(waybills.retryWaybill(ORDER_ID, ACTOR)).thenAnswer(inv -> {
            order.markRefunding(CancelReason.PICKUP_FAILED, AT);       // 并发：另一运营的取消处置先提交
            return WaybillResult.ok();
        });
        PickupResolution r = service.resolvePickupFailureByRetry(ORDER_ID, ACTOR);
        assertThat(r.outcome()).isEqualTo(PickupResolution.Outcome.ABORTED);
        assertThat(r.reason()).isEqualTo("CONCURRENT_RESOLUTION");
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        assertThat(order.getTimerResumedAt()).isNull();
        verify(orders).findByIdForUpdate(ORDER_ID);
        verifyNoInteractions(audit);
        verify(alerts).alert(contains(LogisticsFixtures.DISPLAY_NO));
    }

    @Test
    @DisplayName("复审 1：重下运单收尾时已无活动 Biteship 运单（被并发取消）→ 中止，不清标记")
    void retryWithoutActiveWaybillAborts() {
        pickupFailed();
        when(waybills.retryWaybill(ORDER_ID, ACTOR)).thenReturn(WaybillResult.ok());
        assertThat(service.resolvePickupFailureByRetry(ORDER_ID, ACTOR).outcome())
                .isEqualTo(PickupResolution.Outcome.ABORTED);
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("复审 1：取消订单进行中「重下运单」已建出新运单 → 收尾加锁校验发现活动运单，不调 4.1 取消、告警、返回中止")
    void cancelInterleavedWithRetry() {
        pickupFailed();
        org.mockito.Mockito.doAnswer(inv -> {
            when(shipments.findActiveByShopOrderId(ORDER_ID))
                    .thenReturn(List.of(LogisticsFixtures.biteshipShipment(2L, "bs_new")));
            return null;
        }).when(waybills).cancelWaybill(ORDER_ID, "others", ACTOR);
        PickupResolution r = service.resolvePickupFailureByCancel(ORDER_ID, ACTOR);
        assertThat(r.outcome()).isEqualTo(PickupResolution.Outcome.ABORTED);
        verifyNoInteractions(refunds, audit);
        verify(orders).findByIdForUpdate(ORDER_ID);
        verify(alerts).alert(contains(LogisticsFixtures.DISPLAY_NO));
    }

    @Test
    @DisplayName("复审 1：取消订单收尾时标记已被清（重下 / 手工已完成）→ 中止，不取消")
    void cancelAfterResolvedAborts() {
        pickupFailed();
        org.mockito.Mockito.doAnswer(inv -> {
            order.clearLogisticsException(AT);
            return null;
        }).when(waybills).cancelWaybill(ORDER_ID, "others", ACTOR);
        assertThat(service.resolvePickupFailureByCancel(ORDER_ID, ACTOR).outcome())
                .isEqualTo(PickupResolution.Outcome.ABORTED);
        verifyNoInteractions(refunds);
    }

    @Test
    @DisplayName("复审 5：重下运单的数据前提（收件信息已清 / 快递编码缺失）在取消旧运单之前校验，不过则 409 且不取消")
    void retryPrecheckBeforeCancel() {
        pickupFailed();
        doThrow(AppException.conflict("收件信息已清除，无法下运单")).when(waybills).requireWaybillData(order);
        assertThatThrownBy(() -> service.resolvePickupFailureByRetry(ORDER_ID, ACTOR))
                .isInstanceOf(AppException.class).hasMessageContaining("收件信息");
        verify(waybills, never()).cancelWaybill(anyLong(), anyString(), any());
        verify(waybills, never()).retryWaybill(anyLong(), any());
    }

    // ===================== ① 揽收失败 =====================

    @Test
    @DisplayName("重下运单：先 cancelWaybill(pickup_delay) 再 retryWaybill；建成 → 清标记、timer_resumed_at、审计")
    void retryResolves() {
        pickupFailed();
        retryCreatesNewWaybill();
        PickupResolution r = service.resolvePickupFailureByRetry(ORDER_ID, ACTOR);
        assertThat(r.resolvedOk()).isTrue();
        InOrder io = inOrder(waybills);
        io.verify(waybills).cancelWaybill(ORDER_ID, "pickup_delay", ACTOR);
        io.verify(waybills).retryWaybill(ORDER_ID, ACTOR);
        assertThat(order.getLogisticsException()).isNull();
        assertThat(order.getTimerResumedAt()).isEqualTo(AT);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_LOGISTICS_PICKUP_FAILURE_RESOLVED), eq("SHOP_ORDER"),
                eq(TOKEN), contains("PICKUP_FAILED"));
    }

    @Test
    @DisplayName("重下运单失败 → 标记保留、返回 5.1 失败原因")
    void retryWaybillFails() {
        pickupFailed();
        when(waybills.retryWaybill(ORDER_ID, ACTOR)).thenReturn(WaybillResult.failed("REJECTED:x"));
        PickupResolution r = service.resolvePickupFailureByRetry(ORDER_ID, ACTOR);
        assertThat(r.outcome()).isEqualTo(PickupResolution.Outcome.WAYBILL_FAILED);
        assertThat(r.reason()).isEqualTo("REJECTED:x");
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        verify(audit, never()).record(any(), eq(AuditActions.SHOP_LOGISTICS_PICKUP_FAILURE_RESOLVED), any(), any(),
                any());
    }

    @Test
    @DisplayName("拍板③：旧运单取消失败（不可取消 / 不确定）→ 三种处置都中止，订单与标记都不动、原因返回")
    void cancelFailureAborts() {
        pickupFailed();
        doThrow(AppException.conflict("Biteship 运单已在 picked，无法取消")).when(waybills)
                .cancelWaybill(anyLong(), anyString(), any());
        PickupResolution retry = service.resolvePickupFailureByRetry(ORDER_ID, ACTOR);
        assertThat(retry.outcome()).isEqualTo(PickupResolution.Outcome.ABORTED);
        assertThat(retry.reason()).contains("picked");

        doThrow(AppException.serviceUnavailable("Biteship 暂不可用")).when(waybills)
                .cancelWaybill(anyLong(), anyString(), any());
        assertThat(service.resolvePickupFailureByManualWaybill(ORDER_ID, Carrier.JNE, "JN1", 9_000L, ACTOR)
                .outcome()).isEqualTo(PickupResolution.Outcome.ABORTED);
        assertThat(service.resolvePickupFailureByCancel(ORDER_ID, ACTOR).outcome())
                .isEqualTo(PickupResolution.Outcome.ABORTED);

        verify(waybills, never()).retryWaybill(anyLong(), any());
        verifyNoInteractions(fulfillment, refunds, audit);
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("转手工运单：先 cancelWaybill(change_courier) 再登记手工运单 → 清标记")
    void manualResolves() {
        pickupFailed();
        when(shipments.findByCarrierAndTrackingNo("JNE", "JN1")).thenReturn(Optional.empty());
        PickupResolution r = service.resolvePickupFailureByManualWaybill(ORDER_ID, Carrier.JNE, " JN1 ", 9_000L,
                ACTOR);
        assertThat(r.resolvedOk()).isTrue();
        InOrder io = inOrder(waybills, fulfillment);
        io.verify(waybills).cancelWaybill(ORDER_ID, "change_courier", ACTOR);
        io.verify(fulfillment).shipManualWithAudit(TOKEN, Carrier.JNE, "JN1", 9_000L, ACTOR);
        assertThat(order.getLogisticsException()).isNull();
    }

    @Test
    @DisplayName("转手工运单：单号重复在取消旧运单之前就 409（不先把旧运单取消掉）")
    void manualDuplicateBeforeCancel() {
        pickupFailed();
        when(shipments.findByCarrierAndTrackingNo("JNE", "JN1"))
                .thenReturn(Optional.of(LogisticsFixtures.biteshipShipment(9L, "bs_9")));
        assertThatThrownBy(() -> service.resolvePickupFailureByManualWaybill(ORDER_ID, Carrier.JNE, "JN1", 0L,
                ACTOR)).isInstanceOf(AppException.class);
        verify(waybills, never()).cancelWaybill(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("取消订单：先 cancelWaybill(others) 再调 4.1 整单取消（PICKUP_FAILED）；标记保留作历史")
    void cancelResolves() {
        pickupFailed();
        PickupResolution r = service.resolvePickupFailureByCancel(ORDER_ID, ACTOR);
        assertThat(r.resolvedOk()).isTrue();
        InOrder io = inOrder(waybills, refunds);
        io.verify(waybills).cancelWaybill(ORDER_ID, "others", ACTOR);
        io.verify(refunds).cancelWhole(eq(TOKEN), eq(CancelReason.PICKUP_FAILED), anyString(), eq(ACTOR));
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_LOGISTICS_PICKUP_FAILURE_RESOLVED), eq("SHOP_ORDER"),
                eq(TOKEN), anyString());
    }

    @Test
    @DisplayName("前置异常类型不符（非 PICKUP_FAILED / 无标记）→ 409，不调 Biteship")
    void pickupPreconditions() {
        assertThatThrownBy(() -> service.resolvePickupFailureByRetry(ORDER_ID, ACTOR))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(409));
        order.flagLogisticsException(LogisticsException.RETURNED, NOW);
        assertThatThrownBy(() -> service.resolvePickupFailureByCancel(ORDER_ID, ACTOR))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(waybills);
    }

    @Test
    @DisplayName("重下运单只允许待发货（揽收失败后又被揽收的已发货订单 409）")
    void retryRequiresPendingShipment() {
        pickupFailed();
        order.markShipped(NOW);
        assertThatThrownBy(() -> service.resolvePickupFailureByRetry(ORDER_ID, ACTOR))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(waybills);
    }

    @Test
    @DisplayName("互斥：处置完成后再处置同一订单 → 409（标记已清）")
    void mutuallyExclusive() {
        pickupFailed();
        retryCreatesNewWaybill();
        service.resolvePickupFailureByRetry(ORDER_ID, ACTOR);
        assertThatThrownBy(() -> service.resolvePickupFailureByCancel(ORDER_ID, ACTOR))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(refunds);
    }

    // ===================== ② 被退回 =====================

    @Test
    @DisplayName("confirmReturned(COURIER_FAULT) → confirmed + claim_pending + 审计；USER_FAULT 不置位")
    void confirmReturned() {
        order.markShipped(NOW);
        order.flagLogisticsException(LogisticsException.RETURNED, NOW);
        service.confirmReturned(ORDER_ID, ReturnCause.COURIER_FAULT, ACTOR);
        assertThat(order.isLogisticsExceptionConfirmed()).isTrue();
        assertThat(order.isClaimPending()).isTrue();
        assertThat(order.hasUserVisibleDeliveryIssue()).isTrue();
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_LOGISTICS_EXCEPTION_CONFIRMED), eq("SHOP_ORDER"),
                eq(TOKEN), contains("COURIER_FAULT"));

        ShopOrder other = LogisticsFixtures.paidOrder(false);
        other.markShipped(NOW);
        other.flagLogisticsException(LogisticsException.RETURNED, NOW);
        when(orders.findById(7L)).thenReturn(Optional.of(other));
        service.confirmReturned(7L, ReturnCause.USER_FAULT, ACTOR);
        assertThat(other.isLogisticsExceptionConfirmed()).isTrue();
        assertThat(other.isClaimPending()).as("AC8.3").isFalse();
    }

    @Test
    @DisplayName("confirmReturned：当前异常不是 RETURNED → 409")
    void confirmReturnedRequiresReturned() {
        order.markShipped(NOW);
        assertThatThrownBy(() -> service.confirmReturned(ORDER_ID, ReturnCause.COURIER_FAULT, ACTOR))
                .isInstanceOf(AppException.class);
        order.flagLogisticsException(LogisticsException.STALLED, NOW);
        assertThatThrownBy(() -> service.confirmReturned(ORDER_ID, ReturnCause.COURIER_FAULT, ACTOR))
                .isInstanceOf(AppException.class);
        assertThat(order.isClaimPending()).isFalse();
    }

    @Test
    @DisplayName("reshipAfterCourierFault：重发成功 → 清标记、claim_pending 保留；失败 → 标记保留；未确认 → 409")
    void reship() {
        order.markShipped(NOW);
        order.flagLogisticsException(LogisticsException.RETURNED, NOW);
        assertThatThrownBy(() -> service.reshipAfterCourierFault(ORDER_ID, ACTOR)).isInstanceOf(AppException.class);
        verifyNoInteractions(waybills);

        service.confirmReturned(ORDER_ID, ReturnCause.COURIER_FAULT, ACTOR);
        when(waybills.reshipForCourierFault(ORDER_ID, ACTOR)).thenReturn(WaybillResult.failed("REJECTED:y"));
        assertThat(service.reshipAfterCourierFault(ORDER_ID, ACTOR).created()).isFalse();
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.RETURNED);

        when(waybills.reshipForCourierFault(ORDER_ID, ACTOR)).thenReturn(WaybillResult.ok());
        assertThat(service.reshipAfterCourierFault(ORDER_ID, ACTOR).created()).isTrue();
        assertThat(order.getLogisticsException()).isNull();
        assertThat(order.isClaimPending()).isTrue();
        assertThat(order.getTimerResumedAt()).isEqualTo(AT);
    }

    // ===================== ④ 丢件 / 破损 =====================

    @Test
    @DisplayName("markLostOrDamaged：LOST / DAMAGED → confirmed + claim_pending；订单状态不变；已完成也可标")
    void markLostDamaged() {
        order.markShipped(NOW);
        service.markLostOrDamaged(ORDER_ID, LogisticsException.LOST, ACTOR);
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.LOST);
        assertThat(order.isLogisticsExceptionConfirmed()).isTrue();
        assertThat(order.isClaimPending()).isTrue();
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_LOGISTICS_EXCEPTION_CONFIRMED), eq("SHOP_ORDER"),
                eq(TOKEN), contains("LOST"));
    }

    @Test
    @DisplayName("markLostOrDamaged：非 LOST / DAMAGED 类型 422；待发货订单 409")
    void markLostDamagedRejects() {
        assertThatThrownBy(() -> service.markLostOrDamaged(ORDER_ID, LogisticsException.RETURNED, ACTOR))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.markLostOrDamaged(ORDER_ID, LogisticsException.DAMAGED, ACTOR))
                .isInstanceOf(AppException.class);
        assertThat(order.getLogisticsException()).isNull();
    }

    // ===================== 清除 / 索赔 =====================

    @Test
    @DisplayName("clearException：清除 + 审计；再清幂等 false 不审计；claim_pending 不动")
    void clearIdempotent() {
        order.markShipped(NOW);
        service.markLostOrDamaged(ORDER_ID, LogisticsException.DAMAGED, ACTOR);
        assertThat(service.clearException(ORDER_ID, ACTOR)).isTrue();
        assertThat(order.getLogisticsException()).isNull();
        assertThat(order.isClaimPending()).isTrue();
        assertThat(service.clearException(ORDER_ID, ACTOR)).isFalse();
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_LOGISTICS_EXCEPTION_CLEARED), eq("SHOP_ORDER"),
                eq(TOKEN), contains("DAMAGED"));
    }

    @Test
    @DisplayName("setClaimPending(false)：唯一清除入口，写审计；值未变不审计")
    void claimPending() {
        service.setClaimPending(ORDER_ID, false, ACTOR);
        verifyNoInteractions(audit);
        service.setClaimPending(ORDER_ID, true, ACTOR);
        assertThat(order.isClaimPending()).isTrue();
        service.setClaimPending(ORDER_ID, false, ACTOR);
        assertThat(order.isClaimPending()).isFalse();
        verify(audit, org.mockito.Mockito.times(2)).record(eq(ACTOR),
                eq(AuditActions.SHOP_LOGISTICS_CLAIM_PENDING_CHANGED), eq("SHOP_ORDER"), eq(TOKEN), anyString());
    }

    @Test
    @DisplayName("不存在的订单 → 404")
    void notFound() {
        when(orders.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.clearException(999L, ACTOR))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(404));
    }

    // ===================== 列表 =====================

    @Test
    @DisplayName("listOpen：行含展示号 / token / 类型 / 时刻 / 确认 / 待索赔 / 最新 Biteship 状态，不含收件人")
    void listOpen() {
        order.flagLogisticsException(LogisticsException.PICKUP_FAILED, NOW);
        when(orders.findLogisticsExceptionOpen(eq(LogisticsException.PICKUP_FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        var s = LogisticsFixtures.biteshipShipment(1L, "bs_1");
        s.applyProviderStatus("courier_not_found", NOW.plusSeconds(5), null);
        when(shipments.findActiveByShopOrderId(ORDER_ID)).thenReturn(List.of(s));
        var page = service.listOpen(LogisticsException.PICKUP_FAILED, 0);
        assertThat(page.getContent()).hasSize(1);
        var row = page.getContent().get(0);
        assertThat(row.displayNo()).isEqualTo(LogisticsFixtures.DISPLAY_NO);
        assertThat(row.orderToken()).isEqualTo(TOKEN);
        assertThat(row.exceptionType()).isEqualTo("PICKUP_FAILED");
        assertThat(row.flaggedAt()).isEqualTo(NOW);
        assertThat(row.confirmed()).isFalse();
        assertThat(row.latestProviderStatus()).isEqualTo("courier_not_found");
        assertThat(row.toString()).doesNotContain("Budi");
    }

    @Test
    @DisplayName("listClaimPending 走待索赔查询")
    void listClaimPending() {
        order.setClaimPending(true);
        when(orders.findClaimPending(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(order)));
        assertThat(service.listClaimPending(0).getContent()).singleElement()
                .satisfies(r -> assertThat(r.claimPending()).isTrue());
    }
}
