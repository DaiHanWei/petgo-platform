package com.tailtopia.shop.logistics;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/** v1.4.2 shop Story 5.1 L0 夹具：真实实体（不碰 DB）。 */
final class LogisticsFixtures {

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final long ORDER_ID = 100L;
    static final long USER = 9L;
    static final String TOKEN = "ord-tok";
    static final String DISPLAY_NO = "TOKO-20261105-ABC123";

    private LogisticsFixtures() {
    }

    static AddressSnapshot addr() {
        return new AddressSnapshot("Budi Santoso", "+62 812-3456-789", "DKI Jakarta", "Jakarta Selatan",
                "Kebayoran Baru", "Jl. Melawai IV No. 12", "12160");
    }

    /** 已付款待发货：商品 340,000、满减 25,000、运费 28,000 − 15,000、快递 jne/reg。 */
    static ShopOrder paidOrder(boolean insured) {
        return paidOrder(insured, addr(), "jne", "reg");
    }

    static ShopOrder paidOrder(boolean insured, AddressSnapshot ship, String courier, String service) {
        long insurance = insured ? 1_575L : 0L;
        long payable = 340_000L - 25_000L + 28_000L - 15_000L + insurance;
        ShopOrder o = ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot(TOKEN, DISPLAY_NO, NOW, USER, ship, null,
                CheckoutSource.CART, 340_000L, 25_000L, 28_000L, 15_000L, ShippingDiscountSource.FREE_SHIPPING_RULE,
                insurance, insured, payable, null, null, courier, service, "JNE", "Reguler", "2 - 3", 77L, false));
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.applyPaymentSplit(PayChannel.QRIS, 0L, payable);
        o.markPaid(NOW);
        return o;
    }

    static ShopOrderLine line(long id, long skuId, String name, String spec, long unitPrice, int qty) {
        ShopOrderLine l = ShopOrderLine.of(ORDER_ID, skuId, name, spec, unitPrice, qty, ReturnPolicy.RETURNABLE);
        ReflectionTestUtils.setField(l, "id", id);
        return l;
    }

    static Shipment biteshipShipment(long id, String biteshipOrderId) {
        Shipment s = Shipment.biteship(ORDER_ID, "JNE", "jne", "reg", biteshipOrderId, DISPLAY_NO + "-1", "WB1",
                null, 10_000L, 0L, NOW);
        s.applyProviderStatus("confirmed", NOW, null);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }
}
