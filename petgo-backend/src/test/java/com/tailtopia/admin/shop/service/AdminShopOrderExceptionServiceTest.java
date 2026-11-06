package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.event.RefundSucceededEvent;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.pricing.RefundQuote;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 4.1 AC7 —— 异常订单两个处置方法委托到 {@link ShopOrderRefundService}（原因 OUT_OF_STOCK）。 */
class AdminShopOrderExceptionServiceTest {

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShopOrderLineRepository lines = mock(ShopOrderLineRepository.class);
    private final ShopOrderRefundService refunds = mock(ShopOrderRefundService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final com.tailtopia.shop.order.repository.ShipmentRepository shipments =
            mock(com.tailtopia.shop.order.repository.ShipmentRepository.class);
    private final com.tailtopia.account.repository.AccountDeletionRepository deletions =
            mock(com.tailtopia.account.repository.AccountDeletionRepository.class);
    private final AdminShopOrderExceptionService service = new AdminShopOrderExceptionService(orders, lines,
            mock(SkuInventoryRepository.class), refunds, audit, mock(NotificationService.class),
            mock(com.tailtopia.shop.order.refund.ShopOrderRefundRepository.class), deletions, shipments);
    private final ShopOrder order = paidOrder();

    private static ShopOrder paidOrder() {
        ShopOrder o = ShopOrder.place("ord-1", "TOKO-1", Instant.now(), 9L, 200_000L, 20_000L, 0L,
                new AddressSnapshot("Budi", "+628", "DKI", "Jaksel", "Keb", "Jl. 1", "12160"));
        ReflectionTestUtils.setField(o, "id", 1L);
        o.markPaid(Instant.now());
        o.flagShortage(7L, "缺 1 袋", Instant.now());   // V1.4.2 shop-admin 5.1：只处置队列里的单（来源 = 缺货）
        return o;
    }

    private static ShopOrderRefund refund(RefundScope scope, long coin, long cash) {
        return ShopOrderRefund.create("rf", 1L, new RefundQuote(scope, scope == RefundScope.LINE ? 5L : null,
                scope == RefundScope.LINE ? 1 : null, CancelReason.OUT_OF_STOCK, coin + cash, 0L, 0L, coin + cash,
                coin, cash, false), 7L, Instant.now());
    }

    @Test
    @DisplayName("整单取消（平台原因 · 缺货来源）→ refundService.cancelWhole(OUT_OF_STOCK)；Outcome 币段 / 现金段取自退款单，补偿恒 0")
    void cancelWholeDelegates() {
        when(orders.findByPublicToken("ord-1")).thenReturn(Optional.of(order));
        when(refunds.cancelWhole("ord-1", CancelReason.OUT_OF_STOCK, null, "缺货", 7L))
                .thenReturn(new ShopOrderRefundService.Outcome(order, refund(RefundScope.WHOLE, 20_000L, 200_000L)));

        var out = service.cancelWholeOrder("ord-1", " 缺货 ", 7L);

        verify(refunds).cancelWhole("ord-1", CancelReason.OUT_OF_STOCK, null, "缺货", 7L);
        assertThat(out.coinRefunded()).isEqualTo(20_000L);
        assertThat(out.compensationPremium()).isZero();
        assertThat(out.cashRefundDue()).isEqualTo(200_000L);
        verify(audit).record(eq(7L), eq("SHOP_ORDER_EXCEPTION_HANDLED"), eq("SHOP_ORDER"), eq("ord-1"), anyString());
    }

    @Test
    @DisplayName("部分取消 → refundService.cancelLine(OUT_OF_STOCK)，开始退钱")
    void cancelLineDelegates() {
        when(orders.findByPublicToken("ord-1")).thenReturn(Optional.of(order));
        ShopOrderLine line = ShopOrderLine.of(1L, 11L, "P", "S", 100_000L, 2, ReturnPolicy.RETURNABLE);
        ReflectionTestUtils.setField(line, "id", 5L);
        when(lines.findById(5L)).thenReturn(Optional.of(line));
        when(refunds.cancelLine("ord-1", 5L, 1, CancelReason.OUT_OF_STOCK, "缺 1 件", 7L))
                .thenReturn(new ShopOrderRefundService.Outcome(order, refund(RefundScope.LINE, 10_000L, 90_000L)));

        service.cancelLine("ord-1", 5L, 1, "缺 1 件", 7L);

        verify(refunds).cancelLine("ord-1", 5L, 1, CancelReason.OUT_OF_STOCK, "缺 1 件", 7L);
    }

    @Test
    @DisplayName("🔴 不再直接动钱包、不再发 shop-exception:* 扣回事件（同一笔退款不经新旧两条路径扣两次）")
    void noDirectWalletOrEvents() throws Exception {
        assertThat(Arrays.stream(AdminShopOrderExceptionService.class.getDeclaredFields()).map(Field::getType))
                .doesNotContain(PawCoinWalletService.class, ApplicationEventPublisher.class);
        String src = Files.readString(Path.of(
                "src/main/java/com/tailtopia/admin/shop/service/AdminShopOrderExceptionService.java"));
        assertThat(src).doesNotContain("shop-exception:").doesNotContain(RefundSucceededEvent.class.getSimpleName())
                .doesNotContain("refundCoinSegment").doesNotContain("payCompensationPremium");
    }

    // ---------- V1.4.2 shop-admin Story 5.1 ----------

    @Test
    @DisplayName("5.1：有活动运单不能部分取消（O-21）→ 409，不调退款服务")
    void cancelLineRejectedWithActiveWaybill() {
        when(orders.findByPublicToken("ord-1")).thenReturn(Optional.of(order));
        ShopOrderLine line = ShopOrderLine.of(1L, 11L, "P", "S", 100_000L, 2, ReturnPolicy.RETURNABLE);
        ReflectionTestUtils.setField(line, "id", 5L);
        when(lines.findById(5L)).thenReturn(Optional.of(line));
        when(shipments.findActiveByShopOrderId(1L)).thenReturn(java.util.List.of(
                org.mockito.Mockito.mock(com.tailtopia.shop.order.domain.Shipment.class)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelLine("ord-1", 5L, 1, "缺 1 件", 7L))
                .isInstanceOfSatisfying(com.tailtopia.shared.error.AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.v142.exception.activeWaybill"));
        org.mockito.Mockito.verifyNoInteractions(refunds);
    }

    @Test
    @DisplayName("5.1：「联系用户后继续」只对缺货来源；运单失败来源 409")
    void continueOnlyForShortage() {
        ShopOrder o = paidOrder();
        o.clearShortageFlag();
        o.recordWaybillFailure(Instant.now(), "REJECTED:x");
        when(orders.findByPublicToken("ord-1")).thenReturn(Optional.of(o));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.contactAndContinue("ord-1", "已沟通", 7L))
                .isInstanceOfSatisfying(com.tailtopia.shared.error.AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.v142.exception.continueOnlyShortage"));
        assertThat(service.source(o)).contains(ExceptionSource.WAYBILL_FAILED);
    }

    @Test
    @DisplayName("5.1 · O-26：已注销用户的异常订单不在队列（转 5.2 线下处理），处置 409")
    void deletedUserNotInQueue() {
        when(orders.findByPublicToken("ord-1")).thenReturn(Optional.of(order));
        when(deletions.findByUserId(9L)).thenReturn(Optional.of(
                org.mockito.Mockito.mock(com.tailtopia.account.domain.AccountDeletion.class)));
        assertThat(service.isCandidate("ord-1")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelWholeOrder("ord-1", "缺货", 7L))
                .isInstanceOfSatisfying(com.tailtopia.shared.error.AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.v142.exception.notInQueue"));
    }

    @Test
    @DisplayName("5.1：揽收失败类别不能从事务内方法落库（须事务外 resolvePickupFailureByCancel）")
    void pickupCancelMustNotGoThroughTransactionalPath() {
        ShopOrder o = paidOrder();
        o.clearShortageFlag();
        o.flagLogisticsException(com.tailtopia.shop.order.domain.LogisticsException.PICKUP_FAILED, Instant.now());
        when(orders.findByPublicToken("ord-1")).thenReturn(Optional.of(o));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelWholeOrder("ord-1", "揽收失败", 7L))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(refunds);
    }
}
