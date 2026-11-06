package com.tailtopia.shop.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.pay.service.PaymentReconciliationService;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** v1.4.2 shop Story 3.3 L0 夹具：全 mock 装配 {@link ShopOrderPaymentService}（不碰 DB / Redis / 网关）。 */
final class ShopPaymentTestRig {

    final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    final ShopOrderLineRepository orderLines = mock(ShopOrderLineRepository.class);
    final InventoryService inventory = mock(InventoryService.class);
    final CheckoutService checkout = mock(CheckoutService.class);
    final PaymentIntentService intents = mock(PaymentIntentService.class);
    final PaymentGateway gateway = mock(PaymentGateway.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final ShopOrderPaidTransition transition = mock(ShopOrderPaidTransition.class);
    final CouponLedgerService coupons = mock(CouponLedgerService.class);
    final PawCoinWalletService wallet = mock(PawCoinWalletService.class);
    final AccountDeletionRepository accountDeletions = mock(AccountDeletionRepository.class);
    final PaymentReconciliationService reconciliation = mock(PaymentReconciliationService.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    final ShopOrderPaymentService service;

    @SuppressWarnings("unchecked")
    ShopPaymentTestRig() {
        ObjectProvider<ShopOrderPaymentService> self = mock(ObjectProvider.class);
        service = new ShopOrderPaymentService(orders, orderLines, inventory, checkout, intents, gateway, self,
                events, transition, coupons, wallet, accountDeletions, reconciliation, redis, alerts);
        lenient().when(self.getObject()).thenReturn(service);
        lenient().when(accountDeletions.findByUserId(anyLong())).thenReturn(Optional.empty());
        lenient().when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderLines.findByOrderIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                ShopOrderLine.of(1L, 11L, "P", "S", 100_000L, 2, ReturnPolicy.RETURNABLE)));
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    /** 把订单拨到「建于 {@code createdAt}」：订单窗 = 建单 + 60 分钟。 */
    static ShopOrder createdAt(ShopOrder o, Instant createdAt) {
        ReflectionTestUtils.setField(o, "createdAt", createdAt);
        ReflectionTestUtils.setField(o, "expiresAt", createdAt.plus(ShopOrder.PAYMENT_WINDOW));
        return o;
    }

    /** 电商意图：建于 {@code createdAt}；{@code ref} 非空即已出码，码失效于 {@code providerExpiry}（可空 = 存量）。 */
    static PaymentIntent intent(long id, String token, Instant createdAt, String ref, Instant providerExpiry) {
        PaymentIntent p = PaymentIntent.create(9L, PaymentPurpose.SHOP_ORDER, PayChannel.QRIS, 220_000L, "IDR",
                token, createdAt.plus(ShopOrder.PAYMENT_WINDOW));
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "createdAt", createdAt);
        if (ref != null) {
            p.attachGatewayRef(ref, new java.util.HashMap<>(java.util.Map.of("payload", "QR-" + token)));
            if (providerExpiry != null) {
                p.markProviderIssued(providerExpiry);
            }
        }
        return p;
    }
}
