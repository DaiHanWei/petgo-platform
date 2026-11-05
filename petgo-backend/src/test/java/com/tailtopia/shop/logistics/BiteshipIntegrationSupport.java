package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1 夹具（v1.4.2 shop Story 5.1）：造一笔已付款待发货的订单，补齐 Biteship 下运单所需的快递快照与发货地。
 * 🔴 需 Docker postgres + redis，云端不跑（待本地验收）。{@code BITESHIP_MODE=stub}（测试默认）。
 */
abstract class BiteshipIntegrationSupport extends ApiIntegrationTest {

    @Autowired
    protected CheckoutService checkout;
    @Autowired
    protected ShopOrderPaymentService payments;
    @Autowired
    protected CartService carts;
    @Autowired
    protected ShippingAddressService addresses;
    @Autowired
    protected AdminShippingZoneService zones;
    @Autowired
    protected AdminShopPawcoinRulesService rules;
    @Autowired
    protected PawCoinWalletService wallet;
    @Autowired
    protected ShopOrderRepository orders;
    @Autowired
    protected JdbcTemplate jdbc;

    protected static final long ACTOR = 1L;

    protected long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "bsl" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "bsl" + n);
    }

    private String seedSku() {
        String pToken = "bp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "bs" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g,
                        package_length_cm, package_width_cm, package_height_cm)
                VALUES (?, ?, '3 kg', 100000, 1000, 20, 15, 10)""", sToken, pid);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 10, 0)", sid);
        return sToken;
    }

    private String seedAddress(long uid) {
        String kec = "Kbsl" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    /** 已付款待发货（纯 PawCoin 结清），并补快递快照 jne/reg 与发货地。 */
    protected ShopOrder paidBiteshipOrder(long uid) {
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "bsl-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String sku = seedSku();
        String addr = seedAddress(uid);
        carts.add(uid, sku, 1);
        ShopOrder order = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, order.getPublicToken(), null);
        jdbc.update("UPDATE shop_orders SET courier_code = 'jne', courier_service_code = 'reg', "
                + "courier_name = 'JNE', courier_service_name = 'Reguler' WHERE public_token = ?",
                order.getPublicToken());
        jdbc.update("UPDATE shipping_settings SET origin_postal_code = '12440', origin_contact_name = 'Gudang', "
                + "origin_contact_phone = '62811000', origin_address = 'Jl. Gudang 1'");
        ShopOrder paid = orders.findByPublicToken(order.getPublicToken()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        return paid;
    }

    protected ShopOrder reload(String token) {
        return orders.findByPublicToken(token).orElseThrow();
    }
}
