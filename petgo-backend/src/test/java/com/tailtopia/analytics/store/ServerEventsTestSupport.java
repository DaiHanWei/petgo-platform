package com.tailtopia.analytics.store;

import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1 基类（analytics-pipeline Story 1.9）：造一笔 Toko QRIS 单并让网关回调到账。造单路径同
 * {@code ShopOrderPaymentIntegrationTest#paidEmitsShopPaymentPaid}。
 */
abstract class ServerEventsTestSupport extends ApiIntegrationTest {

    protected static final long ACTOR = 1L;

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ShopOrderRepository orders;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private CartService carts;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private AdminShopPawcoinRulesService rules;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "srvevt" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "srvevt" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "sep" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "ses" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g, package_length_cm, package_width_cm, package_height_cm)
                VALUES (?, ?, '3 kg', ?, 1000, 20, 15, 10)""", sToken, pid, price);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)", sid, stock);
        return sToken;
    }

    private ShopOrder placeOrder(long uid, String sku) {
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Ksrv" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, sku, 1);
        return checkout.placeOrder(uid, addr, null, null);
    }

    /** 一笔已到账的单：下单人 + 订单 publicToken。 */
    protected record PaidOrder(long userId, String orderToken) {
    }

    /** 下一笔 QRIS 单并让网关回调到账。 */
    protected PaidOrder payOneOrder() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        ShopOrder order = placeOrder(uid, seedSku(10, 285_000L));
        payments.pay(uid, order.getPublicToken(), null);
        String intentToken = orders.findByPublicToken(order.getPublicToken()).orElseThrow().getPaymentIntentToken();
        paymentIntents.applyCallback(new PaymentCallback(intentToken, "gw-" + SEQ.incrementAndGet(),
                GatewayStatus.PAID, Map.of()));
        return new PaidOrder(uid, order.getPublicToken());
    }
}
