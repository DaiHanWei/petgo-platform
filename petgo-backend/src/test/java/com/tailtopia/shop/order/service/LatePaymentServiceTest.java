package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.event.RefundSucceededEvent;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.coupon.service.CouponInvalidException;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.event.ShopOrderReorderedEvent;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 4.2 AC1 / AC2 / AC4 / AC6 / AC7 —— {@link LatePaymentService}（Mockito，不碰 DB）。 */
class LatePaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final long ORIG_ID = 10L;
    private static final long NEW_ID = 20L;
    private static final long USER = 7L;
    private static final long SKU = 300L;
    private static final long DISCOUNT_UC = 41L;
    private static final long FREE_SHIP_UC = 42L;

    private ShopOrderRepository orders;
    private ShopOrderLineRepository orderLines;
    private ShopSkuRepository skus;
    private InventoryService inventory;
    private CouponLedgerService coupons;
    private PawCoinWalletService wallet;
    private ShopTokenGenerator tokens;
    private ShopOrderDisplayNoGenerator displayNos;
    private ShopOrderPaidTransition paidTransition;
    private ShopOrderRefundService refunds;
    private AdminAuditService audit;
    private AccountDeletionRepository accountDeletions;
    private ApplicationEventPublisher events;
    private LatePaymentService service;

    private final List<ShopOrder> saved = new ArrayList<>();
    private final List<ShopOrderLine> savedLines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orders = mock(ShopOrderRepository.class);
        orderLines = mock(ShopOrderLineRepository.class);
        skus = mock(ShopSkuRepository.class);
        inventory = mock(InventoryService.class);
        coupons = mock(CouponLedgerService.class);
        wallet = mock(PawCoinWalletService.class);
        tokens = mock(ShopTokenGenerator.class);
        displayNos = mock(ShopOrderDisplayNoGenerator.class);
        paidTransition = mock(ShopOrderPaidTransition.class);
        refunds = mock(ShopOrderRefundService.class);
        audit = mock(AdminAuditService.class);
        accountDeletions = mock(AccountDeletionRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new LatePaymentService(orders, orderLines, skus, inventory, coupons, wallet, tokens, displayNos,
                paidTransition, refunds, audit, accountDeletions, events,
                mock(com.tailtopia.shop.coupon.repository.UserCouponRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));

        when(tokens.generate()).thenReturn("new-tok");
        when(displayNos.generateUnique(eq(NOW), any())).thenReturn("TOKO-20261105-NEW001");
        when(accountDeletions.findByUserId(anyLong())).thenReturn(Optional.empty());
        // V1.4.2 shop-admin 7.2：锁库存改 tryLock（锁不到的行置 stock_unlocked，不抛异常）
        when(inventory.tryLock(anyLong(), anyLong())).thenReturn(true);
        when(orders.save(any(ShopOrder.class))).thenAnswer(inv -> {
            ShopOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                ReflectionTestUtils.setField(o, "id", NEW_ID);
            }
            saved.add(o);
            return o;
        });
        when(orderLines.save(any(ShopOrderLine.class))).thenAnswer(inv -> {
            savedLines.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(paidTransition.markPaid(any(), eq(PaidSource.REORDER))).thenReturn(true);
    }

    /**
     * 已取消原单：商品 315,000、运费 13,000、运费减免 0、保价 1,575 → 应付 329,575；币 100,000 / 现金 229,575；
     * 满减券 + 免运券各一张（金额口径与 4.1 夹具一致，免运减免 0 只为带券 id）；迟到款 {@code late}。
     */
    private ShopOrder original(ShopOrderStatus status, Long late) {
        ShopOrder o = ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot("orig-tok", "TOKO-20261105-ORIG01",
                NOW.minusSeconds(7_200), USER, ShopOrderTestOrders.SHIP, 555L, CheckoutSource.CART,
                315_000L, 0L, 13_000L, 0L, ShippingDiscountSource.COUPON, 1_575L, true, 329_575L,
                DISCOUNT_UC, FREE_SHIP_UC, "jne", "reg", "JNE", "REG", "2 - 3", 77L, true));
        ReflectionTestUtils.setField(o, "id", ORIG_ID);
        o.applyPaymentSplit(PayChannel.MIXED, 100_000L, 229_575L);
        ReflectionTestUtils.setField(o, "status", status);
        if (late != null) {
            ReflectionTestUtils.setField(o, "latePaidAmount", late);
            ReflectionTestUtils.setField(o, "latePaidAt", NOW.minusSeconds(600));
            ReflectionTestUtils.setField(o, "latePaidIntentId", 88L);
        }
        return o;
    }

    private ShopOrderLine line(int qty) {
        ShopOrderLine l = ShopOrderLine.of(ORIG_ID, SKU, "Royal Canin 2kg", "2kg", 105_000L, qty,
                ReturnPolicy.values()[0]);
        l.attributeTo("REPURCHASE_CARD", "AUTO");
        l.markDiscountEligible(true);
        ReflectionTestUtils.setField(l, "id", 1L);
        return l;
    }

    private ShopOrder arrange(ShopOrderStatus status, Long late, long available) {
        ShopOrder o = original(status, late);
        when(orders.findByPublicToken("orig-tok")).thenReturn(Optional.of(o));
        when(orders.claimLatePayment(eq(ORIG_ID), anyString())).thenAnswer(inv -> {
            ReflectionTestUtils.setField(o, "latePaymentResolution", inv.getArgument(1));
            return 1;
        });
        when(orders.findById(ORIG_ID)).thenReturn(Optional.of(o));
        when(orderLines.findByOrderIdOrderByIdAsc(ORIG_ID)).thenReturn(List.of(line(3)));
        when(inventory.availableBySkuId(List.of(SKU))).thenReturn(Map.of(SKU, available));
        return o;
    }

    private ShopOrder newOrder() {
        return saved.stream().filter(o -> o.getId() == NEW_ID).findFirst().orElseThrow();
    }

    private List<Object> publishedEvents() {
        ArgumentCaptor<Object> c = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(c.capture());
        return c.getAllValues();
    }

    // ===================== AC2.1 / AC1 =====================

    @Test
    @DisplayName("AC2.1：原单非 CANCELLED（重复付款单在履约）→ 409 late-payment-not-reorderable，不抢占处理权")
    void nonCancelledNotReorderable() {
        arrange(ShopOrderStatus.PENDING_SHIPMENT, 229_575L, 10);
        assertThatThrownBy(() -> service.reorderFromLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.LATE_PAYMENT_NOT_REORDERABLE);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.latePayment.notReorderable");
                });
        verify(orders, never()).claimLatePayment(anyLong(), anyString());
    }

    @Test
    @DisplayName("原单不存在 → 404")
    void missingOrder404() {
        when(orders.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reorderFromLatePayment("nope", 1L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.refundLatePayment("nope", 1L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("AC1：claimLatePayment 返回 0（已重下单 / 已退款 / 无迟到款）→ 409 late-payment-already-resolved，后续一步不做")
    void claimLostIsAlreadyResolved() {
        arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        when(orders.claimLatePayment(ORIG_ID, "REORDERED")).thenReturn(0);
        assertThatThrownBy(() -> service.reorderFromLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getType()).isEqualTo(ErrorTypes.LATE_PAYMENT_ALREADY_RESOLVED);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.latePayment.alreadyResolved");
                });
        verifyNoInteractions(inventory, coupons, wallet, paidTransition, events);
        verify(orders, never()).save(any());

        when(orders.claimLatePayment(ORIG_ID, "REFUNDED")).thenReturn(0);
        assertThatThrownBy(() -> service.refundLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.LATE_PAYMENT_ALREADY_RESOLVED));
        verifyNoInteractions(refunds);
    }

    @Test
    @DisplayName("注销用户：重新下单 409 线下处理且不抢占；V1.4.2 shop-admin 7.2 · O-26 退款不再 409 → 照常出 LATE_PAYMENT 退款单（进「注销用户 · 线下处理」）")
    void deletedUserRejected() {
        ShopOrder o = arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        when(accountDeletions.findByUserId(USER)).thenReturn(Optional.of(mock(AccountDeletion.class)));
        assertThatThrownBy(() -> service.reorderFromLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.latePayment.userDeleted"));
        verify(orders, never()).claimLatePayment(anyLong(), anyString());
        // O-26：按设计改断言 —— 退款路径对注销用户放行
        ShopOrderRefund r = mock(ShopOrderRefund.class);
        when(r.getPublicToken()).thenReturn("rf-tok");
        when(refunds.createLatePaymentRefund(o, 229_575L, 1L)).thenReturn(r);
        assertThat(service.refundLatePayment("orig-tok", 1L)).isSameAs(r);
        verify(orders).claimLatePayment(ORIG_ID, "REFUNDED");
    }

    // ===================== AC2.3 ~ AC2.5 =====================

    @Test
    @DisplayName("AC2.3：库存不足 → 409 unavailable-lines 带逐行明细（sku token / 可售 / 需求），不建新单")
    void insufficientStock() {
        arrange(ShopOrderStatus.CANCELLED, 229_575L, 2);
        ShopSku sku = mock(ShopSku.class);
        when(sku.getId()).thenReturn(SKU);
        when(sku.getPublicToken()).thenReturn("sku-tok");
        when(skus.findAllById(List.of(SKU))).thenReturn(List.of(sku));
        assertThatThrownBy(() -> service.reorderFromLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(CheckoutUnavailableException.class, e -> {
                    assertThat(e.getType()).isEqualTo(ErrorTypes.UNAVAILABLE_LINES);
                    assertThat(e.getLines()).singleElement().satisfies(l -> {
                        assertThat(l.skuToken()).isEqualTo("sku-tok");
                        assertThat(l.available()).isEqualTo(2L);
                        assertThat(l.requested()).isEqualTo(3);
                        assertThat(l.reason()).isEqualTo("INSUFFICIENT_STOCK");
                    });
                });
        verify(orders, never()).save(any());
        verifyNoInteractions(wallet, paidTransition);
    }

    @Test
    @DisplayName("AC2.3 复审：查可售后被别单买走（7.2 起 tryLock 失败；唯一一行也锁不到 = 全部缺货）→ unavailable-lines 带该行明细，不扣币、不转已付款")
    void stockRaceBetweenCheckAndLock() {
        arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        when(inventory.tryLock(SKU, 3)).thenReturn(false);
        ShopSku sku = mock(ShopSku.class);
        when(sku.getPublicToken()).thenReturn("sku-tok");
        when(sku.getId()).thenReturn(SKU);
        when(skus.findAllById(any())).thenReturn(List.of(sku));
        assertThatThrownBy(() -> service.reorderFromLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(CheckoutUnavailableException.class, e -> {
                    assertThat(e.getType()).isEqualTo(ErrorTypes.UNAVAILABLE_LINES);
                    assertThat(e.getLines()).singleElement().satisfies(l -> {
                        assertThat(l.skuToken()).isEqualTo("sku-tok");
                        assertThat(l.available()).isZero();
                        assertThat(l.requested()).isEqualTo(3);
                        assertThat(l.reason()).isEqualTo("INSUFFICIENT_STOCK");
                    });
                });
        verifyNoInteractions(wallet, paidTransition, coupons);
    }

    @Test
    @DisplayName("AC2.4：原券被占用 / 用掉 / 作废 → 409 reorder-coupon-unavailable")
    void couponUnavailable() {
        arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        org.mockito.Mockito.doThrow(new CouponInvalidException("uc-tok")).when(coupons)
                .relockForReorder(USER, FREE_SHIP_UC, NEW_ID, NOW);
        assertThatThrownBy(() -> service.reorderFromLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getType()).isEqualTo(ErrorTypes.REORDER_COUPON_UNAVAILABLE);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.latePayment.couponUnavailable");
                });
        verifyNoInteractions(wallet, paidTransition);
    }

    @Test
    @DisplayName("AC2.5：币不足（钱包抛 pawcoin-insufficient）→ 409 reorder-coin-insufficient，不退多余、不转已付款")
    void coinInsufficient() {
        arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        org.mockito.Mockito.doThrow(AppException.pawcoinInsufficient("PawCoin 余额不足")).when(wallet)
                .debit(eq(USER), eq(100_000L), any(), anyString(), anyLong(), anyString());
        assertThatThrownBy(() -> service.reorderFromLatePayment("orig-tok", 1L))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getType()).isEqualTo(ErrorTypes.REORDER_COIN_INSUFFICIENT);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.latePayment.coinInsufficient");
                });
        verify(wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
        verifyNoInteractions(paidTransition);
    }

    // ===================== AC4 正常路径 =====================

    @Test
    @DisplayName("AC4 正常路径（迟到款 = 现金段）：调用顺序 claim → 锁库存 → 锁券 → 扣币 → 拆分 → markPaid(REORDER) → 发事件")
    void happyPathOrder() {
        ShopOrder o = arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);

        ReorderOutcome out = service.reorderFromLatePayment("orig-tok", 1L);

        ShopOrder n = newOrder();
        InOrder io = inOrder(orders, inventory, coupons, wallet, paidTransition, events);
        io.verify(orders).claimLatePayment(ORIG_ID, "REORDERED");
        io.verify(inventory).tryLock(SKU, 3);
        io.verify(coupons).relockForReorder(USER, DISCOUNT_UC, NEW_ID, NOW);
        io.verify(coupons).relockForReorder(USER, FREE_SHIP_UC, NEW_ID, NOW);
        io.verify(wallet).debit(USER, 100_000L, PawCoinTxnType.SPEND, "SHOP_ORDER", NEW_ID, "shop-order:new-tok");
        io.verify(paidTransition).markPaid(n, PaidSource.REORDER);
        io.verify(events).publishEvent(any(ShopOrderReorderedEvent.class));

        // 无多出 → 不退 PawCoin
        verify(wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
        assertThat(out).isEqualTo(new ReorderOutcome("new-tok", "TOKO-20261105-NEW001", 100_000L, 229_575L, 0L));
        // 原单保持 CANCELLED、处置结果 REORDERED
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(o.getLatePaymentResolution()).isEqualTo("REORDERED");
    }

    @Test
    @DisplayName("AC4.1 / AC4.6：新单 REORDER、指回原单、沿用原单门店与全部订单头快照、不挂支付意图、拆分 MIXED")
    void newOrderSnapshot() {
        ShopOrder o = arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        service.reorderFromLatePayment("orig-tok", 1L);
        ShopOrder n = newOrder();

        assertThat(n.getCheckoutSource()).isEqualTo(CheckoutSource.REORDER);
        assertThat(n.getReorderOfOrderId()).isEqualTo(ORIG_ID);
        assertThat(n.getReferrerMerchantId()).isEqualTo(555L);
        assertThat(n.getPaymentIntentToken()).isNull();
        assertThat(n.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT); // markPaid 为 mock，迁移由 3.2 测试覆盖
        assertThat(n.getExpiresAt()).isEqualTo(NOW.plus(ShopOrder.PAYMENT_WINDOW));
        assertThat(n.getCreatedAt()).isEqualTo(NOW);
        assertThat(n.getDisplayNo()).isEqualTo("TOKO-20261105-NEW001");
        assertThat(n.getUserId()).isEqualTo(USER);
        assertThat(n.getGoodsSubtotal()).isEqualTo(o.getGoodsSubtotal());
        assertThat(n.getDiscountCouponAmount()).isEqualTo(o.getDiscountCouponAmount());
        assertThat(n.getShippingFee()).isEqualTo(o.getShippingFee());
        assertThat(n.getShippingDiscount()).isEqualTo(o.getShippingDiscount());
        assertThat(n.getInsuranceFee()).isEqualTo(1_575L);
        assertThat(n.isInsuranceSelected()).isTrue();
        assertThat(n.getTotalAmount()).isEqualTo(329_575L);
        assertThat(n.getShippingDiscountSource()).isEqualTo(ShippingDiscountSource.COUPON);
        assertThat(n.getDiscountUserCouponId()).isEqualTo(DISCOUNT_UC);
        assertThat(n.getFreeShippingUserCouponId()).isEqualTo(FREE_SHIP_UC);
        assertThat(n.getCourierCode()).isEqualTo("jne");
        assertThat(n.getCourierServiceCode()).isEqualTo("reg");
        assertThat(n.getCourierName()).isEqualTo("JNE");
        assertThat(n.getCourierServiceName()).isEqualTo("REG");
        assertThat(n.getCourierEtaText()).isEqualTo("2 - 3");
        assertThat(n.getShippingQuoteId()).isEqualTo(77L);
        assertThat(n.getCoinSelected()).isTrue();
        assertThat(n.shipTo()).isEqualTo(o.shipTo());
        assertThat(n.getPayChannel()).isEqualTo(PayChannel.MIXED);
        assertThat(n.getCoinAmount()).isEqualTo(100_000L);
        assertThat(n.getCashAmount()).isEqualTo(229_575L);
        assertThat(n.getLatePaidAmount()).isNull();

        // AC4.2 订单行逐行复制原快照，refunded_qty = 0
        assertThat(savedLines).singleElement().satisfies(l -> {
            assertThat(l.getOrderId()).isEqualTo(NEW_ID);
            assertThat(l.getSkuId()).isEqualTo(SKU);
            assertThat(l.getUnitPrice()).isEqualTo(105_000L);
            assertThat(l.getQty()).isEqualTo(3);
            assertThat(l.getProductName()).isEqualTo("Royal Canin 2kg");
            assertThat(l.getSpecName()).isEqualTo("2kg");
            assertThat(l.getEntrySource()).isEqualTo("REPURCHASE_CARD");
            assertThat(l.getTriggerType()).isEqualTo("AUTO");
            assertThat(l.isDiscountEligible()).isTrue();
            assertThat(l.getRefundedQty()).isZero();
        });

        // AC4.8 审计：原单 token，摘要含新单展示号与金额
        verify(audit).record(eq(1L), eq(AuditActions.SHOP_ORDER_LATE_PAYMENT_REORDERED), eq("SHOP_ORDER"),
                eq("orig-tok"), org.mockito.ArgumentMatchers.argThat(s -> s.contains("TOKO-20261105-NEW001")
                        && s.contains("229575") && !s.contains("Jl. Test")));

        // AC4.9 事件载荷
        ShopOrderReorderedEvent ev = (ShopOrderReorderedEvent) publishedEvents().stream()
                .filter(e -> e instanceof ShopOrderReorderedEvent).findFirst().orElseThrow();
        assertThat(ev).isEqualTo(new ShopOrderReorderedEvent(ORIG_ID, NEW_ID, USER, "new-tok",
                "TOKO-20261105-NEW001"));
    }

    @Test
    @DisplayName("AC4.5 多退：迟到款多 20,425 → credit(REFUND, SHOP_LATE_EXCESS, 原单)，不发 RefundSucceededEvent、不 recordRefund")
    void excessCreditedAsPawcoin() {
        ShopOrder o = arrange(ShopOrderStatus.CANCELLED, 250_000L, 10);
        ReorderOutcome out = service.reorderFromLatePayment("orig-tok", 1L);

        assertThat(out.coinAmount()).isEqualTo(100_000L);
        assertThat(out.cashAmount()).isEqualTo(229_575L);
        assertThat(out.excessToPawcoin()).isEqualTo(20_425L);
        verify(wallet).credit(USER, 20_425L, PawCoinTxnType.REFUND, "SHOP_LATE_EXCESS", ORIG_ID,
                "shop-late-excess:orig-tok");
        assertThat(publishedEvents()).noneMatch(e -> e instanceof RefundSucceededEvent);
        assertThat(o.getRefundedTotal()).isZero();
        assertThat(newOrder().getRefundedTotal()).isZero();
    }

    @Test
    @DisplayName("AC3 少补：迟到款少 29,575 → 新单币 = 原币 + 差额，现金 = 迟到款")
    void shortfallCoveredByCoin() {
        arrange(ShopOrderStatus.CANCELLED, 200_000L, 10);
        ReorderOutcome out = service.reorderFromLatePayment("orig-tok", 1L);
        assertThat(out.coinAmount()).isEqualTo(129_575L);
        assertThat(out.cashAmount()).isEqualTo(200_000L);
        verify(wallet).debit(USER, 129_575L, PawCoinTxnType.SPEND, "SHOP_ORDER", NEW_ID, "shop-order:new-tok");
        assertThat(newOrder().getCoinAmount() + newOrder().getCashAmount()).isEqualTo(329_575L);
    }

    @Test
    @DisplayName("AC3.3：纯 QRIS 原单（币 0、迟到款 = 应付）→ 新单 QRIS、不扣币")
    void pureCashOriginal() {
        ShopOrder o = arrange(ShopOrderStatus.CANCELLED, 329_575L, 10);
        o.applyPaymentSplit(PayChannel.QRIS, 0L, 329_575L);
        service.reorderFromLatePayment("orig-tok", 1L);
        assertThat(newOrder().getPayChannel()).isEqualTo(PayChannel.QRIS);
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("原单无券 → 不调 relockForReorder")
    void noCouponsNoRelock() {
        ShopOrder o = arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        ReflectionTestUtils.setField(o, "discountUserCouponId", null);
        ReflectionTestUtils.setField(o, "freeShippingUserCouponId", null);
        service.reorderFromLatePayment("orig-tok", 1L);
        verify(coupons, never()).relockForReorder(anyLong(), anyLong(), anyLong(), any());
    }

    // ===================== AC6 退款 =====================

    @Test
    @DisplayName("AC6：退款 → claim(REFUNDED) 后 createLatePaymentRefund(order, L, actor)；不 recordRefund、不改状态、不发通知 / 事件")
    void refundPath() {
        ShopOrder o = arrange(ShopOrderStatus.CANCELLED, 229_575L, 10);
        ShopOrderRefund r = mock(ShopOrderRefund.class);
        when(r.getPublicToken()).thenReturn("rf-tok");
        when(refunds.createLatePaymentRefund(o, 229_575L, 1L)).thenReturn(r);

        assertThat(service.refundLatePayment("orig-tok", 1L)).isSameAs(r);

        InOrder io = inOrder(orders, refunds);
        io.verify(orders).claimLatePayment(ORIG_ID, "REFUNDED");
        io.verify(refunds).createLatePaymentRefund(o, 229_575L, 1L);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(o.getRefundedTotal()).isZero();
        assertThat(o.getLatePaymentResolution()).isEqualTo("REFUNDED");
        verifyNoInteractions(events, wallet, coupons, inventory, paidTransition);
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("AC6.1：重复付款单（非 CANCELLED）可退款")
    void duplicatePaymentCanBeRefunded() {
        ShopOrder o = arrange(ShopOrderStatus.PENDING_SHIPMENT, 229_575L, 10);
        ShopOrderRefund r = mock(ShopOrderRefund.class);
        when(refunds.createLatePaymentRefund(o, 229_575L, null)).thenReturn(r);
        assertThat(service.refundLatePayment("orig-tok", null)).isSameAs(r);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    // ===================== AC7 详情关联 =====================

    @Test
    @DisplayName("AC7：原单（REORDERED）→ 新单 token；新单 → 原单展示号与 token；普通单不查库")
    void detailLinks() {
        ShopOrder o = original(ShopOrderStatus.CANCELLED, 229_575L);
        ReflectionTestUtils.setField(o, "latePaymentResolution", "REORDERED");
        ShopOrder n = ShopOrder.placeReorder(o, "new-tok", "TOKO-20261105-NEW001", NOW);
        ReflectionTestUtils.setField(n, "id", NEW_ID);
        when(orders.findByReorderOfOrderId(ORIG_ID)).thenReturn(Optional.of(n));
        when(orders.findById(ORIG_ID)).thenReturn(Optional.of(o));

        assertThat(service.linksOf(o)).isEqualTo(new ShopOrderDetailView.ReorderLinks("new-tok", null, null));
        assertThat(service.linksOf(n)).isEqualTo(
                new ShopOrderDetailView.ReorderLinks(null, "TOKO-20261105-ORIG01", "orig-tok"));

        ShopOrder plain = original(ShopOrderStatus.COMPLETED, null);
        org.mockito.Mockito.clearInvocations(orders);
        assertThat(service.linksOf(plain)).isSameAs(ShopOrderDetailView.ReorderLinks.NONE);
        verifyNoInteractions(orders);
    }
}
