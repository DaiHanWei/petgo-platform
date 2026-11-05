package com.tailtopia.shop.order.refund;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * v1.4.2 shop Story 4.1 L0 夹具：{@link ShopOrderRefundService} 全部依赖为 mock，退款单仓库用内存表模拟
 * （save / findByPublicToken / findByOrderId… / exists…），订单为真实实体（O1 形）。不碰 DB。
 */
final class RefundTestRig {

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final long USER = 9L;
    static final long ORDER_ID = 100L;
    static final String ORDER_TOKEN = "ord-tok";

    final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    final ShopOrderLineRepository orderLines = mock(ShopOrderLineRepository.class);
    final ShopOrderRefundRepository refunds = mock(ShopOrderRefundRepository.class);
    final InventoryMovementService movements = mock(InventoryMovementService.class);
    final PawCoinWalletService wallet = mock(PawCoinWalletService.class);
    final CouponLedgerService coupons = mock(CouponLedgerService.class);
    final PawCoinConfigRepository pawcoinConfig = mock(PawCoinConfigRepository.class);
    final AdminAuditService audit = mock(AdminAuditService.class);
    final NotificationService notifications = mock(NotificationService.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final ShopTokenGenerator tokens = mock(ShopTokenGenerator.class);
    final AccountDeletionRepository accountDeletions = mock(AccountDeletionRepository.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<ShopWaybillCanceller> cancellers = mock(ObjectProvider.class);
    final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    final PawCoinConfig config = mock(PawCoinConfig.class);

    /** 内存退款单表（按保存顺序 = 创建顺序）。 */
    final List<ShopOrderRefund> store = new ArrayList<>();
    final AtomicInteger seq = new AtomicInteger();

    final ShopOrderRefundService service;
    ShopOrder order;
    List<ShopOrderLine> lines;

    RefundTestRig() {
        service = new ShopOrderRefundService(orders, orderLines, refunds, movements, wallet, coupons, pawcoinConfig,
                audit, notifications, events, tokens, accountDeletions, cancellers, alerts,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(tokens.generate()).thenAnswer(inv -> "rf" + seq.incrementAndGet());
        when(pawcoinConfig.findAll()).thenReturn(List.of(config));
        when(accountDeletions.findByUserId(anyLong())).thenReturn(Optional.empty());
        when(refunds.save(any(ShopOrderRefund.class))).thenAnswer(inv -> {
            ShopOrderRefund r = inv.getArgument(0);
            if (!store.contains(r)) {
                ReflectionTestUtils.setField(r, "id", (long) (store.size() + 1));
                store.add(r);
            }
            return r;
        });
        when(refunds.findByPublicToken(anyString())).thenAnswer(inv -> store.stream()
                .filter(r -> r.getPublicToken().equals(inv.getArgument(0))).findFirst());
        when(refunds.findByPublicTokenAndOrderId(anyString(), anyLong())).thenAnswer(inv -> store.stream()
                .filter(r -> r.getPublicToken().equals(inv.getArgument(0))
                        && r.getOrderId() == (long) inv.getArgument(1))
                .findFirst());
        when(refunds.findByOrderIdOrderByCreatedAtAscIdAsc(anyLong())).thenAnswer(inv -> store.stream()
                .filter(r -> r.getOrderId() == (long) inv.getArgument(0))
                .sorted(Comparator.comparing(ShopOrderRefund::getId)).toList());
        when(refunds.existsByOrderIdAndScope(anyLong(), any())).thenAnswer(inv -> store.stream()
                .anyMatch(r -> r.getOrderId() == (long) inv.getArgument(0) && r.getScope() == inv.getArgument(1)));
        when(orders.save(any(ShopOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderLines.save(any(ShopOrderLine.class))).thenAnswer(inv -> inv.getArgument(0));
        compensation(10, 0L);
        o1(true, 100_000L);
    }

    void compensation(int rate, long cap) {
        when(config.getCompensationPremiumRate()).thenReturn(rate);
        when(config.getCompensationPremiumCap()).thenReturn(cap);
    }

    void userDeleted() {
        when(accountDeletions.findByUserId(USER)).thenReturn(Optional.of(mock(AccountDeletion.class)));
    }

    /**
     * O1：A 100,000 × 2（适用）、B 50,000 × 1（不适用）、C 30,000 × 3（适用）；满减 25,000；运费 28,000 − 15,000；
     * 保价 1,575 → 应付 329,575。已付款待发货。
     */
    RefundTestRig o1(boolean insured, long coin) {
        long insurance = insured ? 1_575L : 0L;
        long payable = 340_000L - 25_000L + 28_000L - 15_000L + insurance;
        order = ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot(ORDER_TOKEN, "TOKO-20261105-ABCDEF", NOW,
                USER, new AddressSnapshot("Budi", "+628123", "DKI", "Jaksel", "Kebayoran", "Jl. 1", "12160"), null,
                CheckoutSource.CART, 340_000L, 25_000L, 28_000L, 15_000L, ShippingDiscountSource.FREE_SHIPPING_RULE,
                insurance, insured, payable, null, null, "jne", "reg", "JNE", "REG", "2 - 3", 77L, coin > 0));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        long cash = payable - coin;
        order.applyPaymentSplit(coin > 0 && cash > 0 ? PayChannel.MIXED
                : coin > 0 ? PayChannel.PAWCOIN : PayChannel.QRIS, coin, cash);
        order.markPaid(NOW);
        lines = List.of(line(1L, 11L, 100_000L, 2, true), line(2L, 12L, 50_000L, 1, false),
                line(3L, 13L, 30_000L, 3, true));
        when(orders.findByPublicToken(ORDER_TOKEN)).thenReturn(Optional.of(order));
        when(orders.findByPublicTokenAndUserId(ORDER_TOKEN, USER)).thenReturn(Optional.of(order));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderLines.findByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(lines);
        when(orderLines.findById(anyLong())).thenAnswer(inv -> lines.stream()
                .filter(l -> l.getId() == (long) inv.getArgument(0)).findFirst());
        return this;
    }

    void shipped() {
        order.markShipped(NOW);
    }

    void delivered() {
        shipped();
        order.markDelivered(NOW, DeliverySource.ADMIN_MARK);
    }

    void completed() {
        delivered();
        order.markCompleted(NOW, CompletionSource.USER_CONFIRM);
    }

    ShopOrderRefund only() {
        if (store.size() != 1) {
            throw new IllegalStateException("期望恰好一张退款单，实际 " + store.size());
        }
        return store.get(0);
    }

    private static ShopOrderLine line(long id, long skuId, long unitPrice, int qty, boolean eligible) {
        ShopOrderLine l = ShopOrderLine.of(ORDER_ID, skuId, "P" + id, "S", unitPrice, qty, ReturnPolicy.RETURNABLE);
        l.markDiscountEligible(eligible);
        ReflectionTestUtils.setField(l, "id", id);
        return l;
    }
}
