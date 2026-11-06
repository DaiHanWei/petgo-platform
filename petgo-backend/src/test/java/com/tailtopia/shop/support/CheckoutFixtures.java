package com.tailtopia.shop.support;

import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.logistics.CourierRate;
import com.tailtopia.shop.logistics.QuoteResult;
import com.tailtopia.shop.order.domain.ShopPawcoinRules;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** v1.4.2 shop Story 3.1 L0 夹具：商品 / SKU / 地址 / 规则 / 报价（不碰 DB）。 */
public final class CheckoutFixtures {

    public static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private CheckoutFixtures() {
    }

    public static ShopProduct product(long id, String token, boolean active, ReturnPolicy policy) {
        return product(id, token, active, policy, ProductCategory.MAKANAN);
    }

    public static ShopProduct product(long id, String token, boolean active, ReturnPolicy policy,
            ProductCategory category) {
        ShopProduct p = ShopProduct.create(token, "Produk " + id, "B", category, "k" + id, null,
                null, List.of(), Species.DOG, null, null, "<p/>", List.of(), "n", policy, 0);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "active", active);
        return p;
    }

    public static ShopSku sku(long id, String token, long productId, long price, boolean sellable) {
        ShopSku s = ShopSku.create(token, productId, "3 kg", price, null, null);
        ReflectionTestUtils.setField(s, "id", id);
        return sellable ? ShopSkuFixtures.sellable(s) : s;
    }

    public static CartView.CartLine cartLine(String skuToken, String productToken, long price,
            int qty, long available, boolean selected) {
        return new CartView.CartLine(skuToken, productToken, "Produk", "3 kg", price, qty,
                "https://cdn/p.jpg", available, null, "SHOP_HOME", null, selected);
    }

    public static ShippingAddress address(long userId, String token, String kecamatan,
            String kodePos) {
        return ShippingAddress.create(userId, token, new AddressFields("Budi", "+628123456789",
                "DKI Jakarta", "Jakarta Selatan", kecamatan, "Jl. Test 1", kodePos, "Rumah"));
    }

    public static ShippingSettings settings(long threshold, Long cap, int insuranceBp) {
        ShippingSettings s = BeanUtils.instantiateClass(ShippingSettings.class);
        ReflectionTestUtils.setField(s, "id", ShippingSettings.SINGLETON_ID);
        ReflectionTestUtils.setField(s, "freeShippingThreshold", threshold);
        ReflectionTestUtils.setField(s, "freeShippingCap", cap);
        ReflectionTestUtils.setField(s, "insuranceRateBp", insuranceBp);
        return s;
    }

    public static ShopPawcoinRules coinRules(boolean enabled, boolean allowShipping, long max) {
        ShopPawcoinRules r = BeanUtils.instantiateClass(ShopPawcoinRules.class);
        r.apply(enabled, allowShipping, max);
        return r;
    }

    public static CourierRate rate(String code, String service, long price, boolean insurance) {
        return new CourierRate(CourierRate.keyOf(code, service), code, service,
                code.toUpperCase(), service.toUpperCase(), "2 - 3 days", "2 - 3", "days", price,
                insurance);
    }

    /** 已排序的报价（调用方按价格升序传入）。 */
    public static QuoteResult quote(String token, CourierRate... rates) {
        List<CourierRate> list = List.of(rates);
        return new QuoteResult(token, !list.isEmpty(), list,
                list.isEmpty() ? null : list.getFirst().price(), "12160", "hash",
                NOW.plusSeconds(1800));
    }
}
