package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.event.RefundSucceededEvent;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/** L0：未付款取消（v1.4.2 shop Story 3.2 AC6.3 / AC6.4，S-3）。 */
class ShopOrderUnpaidCancelTest {

    private static final long UID = 9L;

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShopOrderLineRepository orderLines = mock(ShopOrderLineRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final CheckoutService checkout = mock(CheckoutService.class);
    private final PaymentIntentService intents = mock(PaymentIntentService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ShopOrderPaidTransition transition = mock(ShopOrderPaidTransition.class);
    private final CouponLedgerService coupons = mock(CouponLedgerService.class);
    private final PawCoinWalletService wallet = mock(PawCoinWalletService.class);
    private final AccountDeletionRepository accountDeletions = mock(AccountDeletionRepository.class);
    private ShopOrderPaymentService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<ShopOrderPaymentService> self = mock(ObjectProvider.class);
        service = new ShopOrderPaymentService(orders, orderLines, inventory, checkout, intents,
                gateway, self, events, transition, coupons, wallet, accountDeletions,
                mock(com.tailtopia.pay.service.PaymentReconciliationService.class),
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(ShopPaymentAlerts.class));
        when(accountDeletions.findByUserId(anyLong())).thenReturn(Optional.empty());
        when(self.getObject()).thenReturn(service);
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderLines.findByOrderIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                ShopOrderLine.of(1L, 11L, "P", "S", 100_000L, 2, ReturnPolicy.RETURNABLE)));
    }

    @Test
    @DisplayName("🔴 新单有币 → credit(REFUND, SHOP_ORDER_VOID, shop-order-void:{token})；释放库存；退券；写原因；作废意图")
    void newOrderRefundsCoin() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(1L, UID, 30_000L, CheckoutSource.CART);
        o.attachPaymentIntent("pi-1");
        when(orders.findByPublicTokenAndUserId("tok1", UID)).thenReturn(Optional.of(o));

        service.cancel(UID, "tok1");

        verify(inventory).release(11L, 2);
        verify(wallet).credit(UID, 30_000L, PawCoinTxnType.REFUND, "SHOP_ORDER_VOID", 1L,
                "shop-order-void:tok1");
        verify(coupons).releaseOnCancel(eq(1L), eq(CouponReleaseMode.RETURN), any());
        verify(intents).failByToken("pi-1", "USER_CANCEL");
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(o.getCancelReason()).isEqualTo(CancelReason.USER_UNPAID_CANCEL);
        assertThat(o.getCancelledAt()).isNotNull();
        // 不是退款：refunded_total 不动、不发 RefundSucceededEvent
        assertThat(o.getRefundedTotal()).isZero();
        assertThat(o.getRefundedCoin()).isZero();
        verify(events, never()).publishEvent(any(RefundSucceededEvent.class));
    }

    @Test
    @DisplayName("🔴 存量单（checkout_source IS NULL）→ 不退币（币从未扣过），其余照常")
    void legacyStockOrderDoesNotRefundCoin() {
        ShopOrder o = ShopOrderTestOrders.legacyStockOrder(2L, UID, 30_000L);
        service.releaseUnpaid(o, CancelReason.PAYMENT_TIMEOUT);

        verify(wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
        verify(coupons).releaseOnCancel(eq(2L), eq(CouponReleaseMode.RETURN), any());
        assertThat(o.getCancelReason()).isEqualTo(CancelReason.PAYMENT_TIMEOUT);
    }

    @Test
    @DisplayName("新单无币（纯现金）→ 不退币")
    void noCoinNoCredit() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(3L, UID, 0L, CheckoutSource.BUY_NOW);
        service.releaseUnpaid(o, CancelReason.PAYMENT_TIMEOUT);
        verify(wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = CancelReason.class, names = {"USER_UNPAID_CANCEL", "PAYMENT_TIMEOUT"})
    @DisplayName("两种未付款原因都调 releaseOnCancel(RETURN)；意图作废原因沿用 USER_CANCEL / TIMEOUT（失败类别口径）")
    void bothReasonsReturnCoupons(CancelReason reason) {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(4L, UID, 0L, CheckoutSource.CART);
        o.attachPaymentIntent("pi-4");
        service.releaseUnpaid(o, reason);
        verify(coupons).releaseOnCancel(eq(4L), eq(CouponReleaseMode.RETURN), any());
        verify(intents).failByToken("pi-4",
                reason == CancelReason.USER_UNPAID_CANCEL ? "USER_CANCEL" : "TIMEOUT");
    }

    @Test
    @DisplayName("超时扫描 → PAYMENT_TIMEOUT")
    void overdueUsesTimeout() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(5L, UID, 10_000L, CheckoutSource.CART);
        org.springframework.test.util.ReflectionTestUtils.setField(o, "expiresAt",
                java.time.Instant.now().minusSeconds(5));
        when(orders.findById(5L)).thenReturn(Optional.of(o));

        service.closeOne(5L);   // v1.4.2 shop 3.3：关单唯一收口（无意图 → 订单窗过即可关）

        assertThat(o.getCancelReason()).isEqualTo(CancelReason.PAYMENT_TIMEOUT);
        verify(wallet).credit(UID, 10_000L, PawCoinTxnType.REFUND, "SHOP_ORDER_VOID", 5L,
                "shop-order-void:tok5");
    }

    @Test
    @DisplayName("已付款订单不能走未付款取消")
    void paidCannotCancel() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(6L, UID, 0L, CheckoutSource.CART);
        o.markPaid(java.time.Instant.now());
        when(orders.findByPublicTokenAndUserId("tok6", UID)).thenReturn(Optional.of(o));
        assertThatThrownBy(() -> service.cancel(UID, "tok6"))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        verify(wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("AC5.5：已结清的纯币单再点 /pay → 直接回当前状态，不建意图、不 409")
    void payOnSettledPureCoinOrderIsCompatible() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(7L, UID, 220_000L, CheckoutSource.LEGACY);
        o.markPaid(java.time.Instant.now());
        when(orders.findByPublicTokenAndUserId("tok7", UID)).thenReturn(Optional.of(o));

        ShopOrderPaymentService.PayResult r = service.pay(UID, "tok7", null);

        assertThat(r).isEqualTo(new ShopOrderPaymentService.PayResult("PENDING_SHIPMENT", null,
                null, null));
        verify(intents, never()).createIntent(anyLong(), any(), any(), anyLong(), anyString(),
                anyString(), any());
    }

    // ---------- 复审修复（D1/D2）：注销与待付款单 ----------

    @Test
    @DisplayName("🔴 账号已受理注销 → 常规取消（超时关单 / 用户取消竞态）不退币、不退券；库存照放、单照取消、意图照作废")
    void deletedAccountSkipsCoinAndCouponReturn() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(8L, UID, 30_000L, CheckoutSource.CART);
        o.attachPaymentIntent("pi-8");
        when(accountDeletions.findByUserId(UID)).thenReturn(Optional.of(mock(AccountDeletion.class)));

        service.releaseUnpaid(o, CancelReason.PAYMENT_TIMEOUT);

        verify(wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
        verify(coupons, never()).releaseOnCancel(anyLong(), any(), any());
        verify(inventory).release(11L, 2);
        verify(intents).failByToken("pi-8", "TIMEOUT");
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("🔴 注销联动路径：即使已有注销记录也退币退券（随后由 PawCoin / 券作废一并作废），原因 USER_UNPAID_CANCEL")
    void accountDeletionPathReturnsAssetsFirst() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(9L, UID, 30_000L, CheckoutSource.CART);
        when(accountDeletions.findByUserId(UID)).thenReturn(Optional.of(mock(AccountDeletion.class)));

        service.releaseUnpaidForAccountDeletion(o);

        verify(wallet).credit(UID, 30_000L, PawCoinTxnType.REFUND, "SHOP_ORDER_VOID", 9L,
                "shop-order-void:tok9");
        verify(coupons).releaseOnCancel(eq(9L), eq(CouponReleaseMode.RETURN), any());
        assertThat(o.getCancelReason()).isEqualTo(CancelReason.USER_UNPAID_CANCEL);
    }

    @Test
    @DisplayName("注销联动路径：订单已非待付款（并发已推进）→ 不动")
    void accountDeletionPathSkipsNonPending() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(10L, UID, 30_000L, CheckoutSource.CART);
        o.markPaid(java.time.Instant.now());
        service.releaseUnpaidForAccountDeletion(o);
        verify(wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }
}
