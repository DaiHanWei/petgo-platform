package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/** v1.4.2 shop Story 3.2 L0 夹具：造订单（不碰 DB）。 */
final class ShopOrderTestOrders {

    static final AddressSnapshot SHIP = new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta",
            "Jakarta Selatan", "Kebayoran", "Jl. Test 1", "12160");

    private ShopOrderTestOrders() {
    }

    /** 新结算模式下的待付款单：商品 200,000 + 运费 20,000，币 {@code coin}、现金其余。 */
    static ShopOrder newModeOrder(long id, long userId, long coin, CheckoutSource source) {
        ShopOrder o = ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot("tok" + id, "TOKO-" + id,
                Instant.now(), userId, SHIP, null, source, 200_000L, 0L, 20_000L, 0L,
                ShippingDiscountSource.NONE, 0L, false, 220_000L, null, null, "jne", "reg", "JNE",
                "REG", "2 - 3", 77L, coin > 0));
        ReflectionTestUtils.setField(o, "id", id);
        long cash = 220_000L - coin;
        o.applyPaymentSplit(coin > 0 && cash > 0 ? PayChannel.MIXED
                : coin > 0 ? PayChannel.PAWCOIN : PayChannel.QRIS, coin, cash);
        return o;
    }

    /** 本版之前下的存量待付款单（{@code checkout_source IS NULL}，币尚未扣）。 */
    static ShopOrder legacyStockOrder(long id, long userId, long coin) {
        ShopOrder o = ShopOrder.place("tok" + id, "TOKO-" + id, Instant.now(), userId, 200_000L,
                20_000L, 0L, SHIP);
        ReflectionTestUtils.setField(o, "id", id);
        long cash = 220_000L - coin;
        o.applyPaymentSplit(coin > 0 && cash > 0 ? PayChannel.MIXED
                : coin > 0 ? PayChannel.PAWCOIN : PayChannel.QRIS, coin, cash);
        return o;
    }
}
