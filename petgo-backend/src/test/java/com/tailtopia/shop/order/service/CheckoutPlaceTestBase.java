package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.support.CheckoutFixtures.address;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.merchant.binding.service.MerchantBindingService;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.logistics.CourierRate;
import com.tailtopia.shop.logistics.PackageItems;
import com.tailtopia.shop.logistics.ShippingQuoteRecord;
import com.tailtopia.shop.logistics.ShippingQuoteRecordRepository;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.shop.shipping.domain.ShippingZone;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * v1.4.2 shop Story 3.2 L0 夹具：{@link CheckoutService#place} 的全部依赖 mock，
 * 结算入参（{@link CheckoutInputs}）直接构造，计价器用真的（纯函数）。不起 Spring、不连库。
 */
abstract class CheckoutPlaceTestBase {

    static final long UID = 42L;
    static final String QUOTE = "q1";
    static final String ORDER_TOKEN = "ordtok";

    @Mock CartService carts;
    @Mock InventoryService inventory;
    @Mock ShopOrderRepository orders;
    @Mock ShopOrderLineRepository orderLines;
    @Mock PawCoinWalletService wallet;
    @Mock ShopTokenGenerator tokens;
    @Mock ShopOrderDisplayNoGenerator displayNos;
    @Mock IdempotencyService idempotency;
    @Mock MerchantBindingService bindings;
    @Mock CheckoutQuoteService quoteChain;
    @Mock LegacyCheckoutAdapter legacy;
    @Mock CheckoutLineResolver loader;
    @Mock ShippingZoneRepository zones;
    @Mock ShippingQuoteRecordRepository quoteRecords;
    @Mock CouponLedgerService coupons;
    @Mock ShopOrderPaidTransition paidTransition;

    CheckoutService service;
    final List<ShopOrderLine> savedLines = new ArrayList<>();
    ShopOrder savedOrder;
    final ShippingAddress addr = address(UID, "a1", "Kebayoran", "12160");

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUpBase() {
        ObjectProvider<CheckoutService> self = mock(ObjectProvider.class);
        service = new CheckoutService(carts, inventory, orders, orderLines, wallet, tokens,
                displayNos, idempotency, bindings, quoteChain, legacy, loader, zones, quoteRecords,
                coupons, paidTransition, self);
        when(self.getObject()).thenReturn(service);
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(idempotency.tryClaim(any(), any())).thenReturn(true);
        when(tokens.generate()).thenReturn(ORDER_TOKEN);
        when(displayNos.generateUnique(any(), any())).thenReturn("TOKO-20261105-ABCDEF");
        when(zones.findByKecamatanAndActiveTrue(anyString()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(ShippingZone.class)));
        when(orders.save(any(ShopOrder.class))).thenAnswer(inv -> {
            ShopOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                ReflectionTestUtils.setField(o, "id", 900L);
            }
            savedOrder = o;
            return o;
        });
        when(orderLines.save(any(ShopOrderLine.class))).thenAnswer(inv -> {
            ShopOrderLine l = inv.getArgument(0);
            savedLines.add(l);
            return l;
        });
        when(bindings.referrerOf(anyLong())).thenReturn(null);
        when(paidTransition.markPaid(any(), any())).thenReturn(true);
        when(quoteRecords.findByPublicToken(anyString())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------ 构造

    static ResolvedCheckoutLine line(ShopSku sku, String productToken, int qty, String reason,
            Long available) {
        return new ResolvedCheckoutLine(sku.getPublicToken(), productToken, "Produk",
                sku.getSpecName(), sku.getPrice(), qty, null, ReturnPolicy.RETURNABLE, reason,
                available, ProductCategory.MAKANAN, null, null, sku);
    }

    static ResolvedCheckoutLine line(ShopSku sku, int qty) {
        return line(sku, "p" + sku.getPublicToken(), qty, null, 10L);
    }

    CheckoutInputs inputs(CheckoutPreviewRequest.Source source, CartView cart,
            List<ResolvedCheckoutLine> lines, List<OwnedCoupon> candidates, FreeShippingRule rule,
            boolean coinEnabled, long balance, ShippingAddress address) {
        return new CheckoutInputs(source, cart, lines, address, candidates, Map.of(), rule, 50,
                coinEnabled, true, 1_000_000L, balance);
    }

    CheckoutInputs inputs(List<ResolvedCheckoutLine> lines, List<OwnedCoupon> candidates,
            long balance) {
        return inputs(CheckoutPreviewRequest.Source.CART, null, lines, candidates,
                new FreeShippingRule(0L, null), true, balance, addr);
    }

    void givenInputs(CheckoutInputs in) {
        when(loader.load(eq(UID), any(), any())).thenReturn(in);
    }

    /** 绑定到 {@code lines} 的报价（目的邮编 12160，30 分钟后过期）。 */
    ShippingQuoteRecord givenQuote(List<ResolvedCheckoutLine> lines, CourierRate... rates) {
        return givenQuote("12160", PackageItems.itemsHash(lines.stream()
                .map(ResolvedCheckoutLine::toPackageItem).toList()),
                Instant.now().plus(Duration.ofMinutes(30)), rates);
    }

    ShippingQuoteRecord givenQuote(String dest, String hash, Instant expiresAt,
            CourierRate... rates) {
        ShippingQuoteRecord q = ShippingQuoteRecord.create(QUOTE, UID, "STUB", "00000", dest, hash,
                List.of(rates), null, Instant.now().minusSeconds(60), expiresAt);
        ReflectionTestUtils.setField(q, "id", 77L);
        when(quoteRecords.findByPublicToken(QUOTE)).thenReturn(Optional.of(q));
        return q;
    }
}
