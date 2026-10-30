package com.tailtopia.merchant.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.merchant.support.MerchantStoresFixture;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：App 填码绑定门店（契约第 1 节 TailTopia 侧验收，FR-7 / FR-40）+ 下单写归属门店、付款写 paid_at、
 * 签收写 return_window_ends_at（契约第 2 节）。需 Docker postgres + redis。
 */
class MerchantBindingIntegrationTest extends ApiIntegrationTest {

    private static final String PATH = "/api/v1/me/merchant-binding";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CartService carts;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private com.tailtopia.support.VetTestSupport vets;

    private String bind(long uid, String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    @DisplayName("无效码 / 停用门店的码 → 422；去空格后命中启用门店 → 绑定；再填 → 409；停用后已有绑定不受影响")
    void bindingRules() throws Exception {
        long uid = newUser().getId();
        var active = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        var disabled = MerchantStoresFixture.insert(jdbc, "DISABLED");

        mvc.perform(get(PATH).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bound").value(false));

        mvc.perform(post(PATH).header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content(bind(uid, "999999x")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/merchant-code-invalid"));
        mvc.perform(post(PATH).header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content(bind(uid, disabled.inviteCode())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/merchant-code-invalid"));

        String spaced = " " + active.inviteCode().substring(0, 3) + " " + active.inviteCode().substring(3) + " ";
        mvc.perform(post(PATH).header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content(bind(uid, spaced)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound").value(true))
                .andExpect(jsonPath("$.inviteCode").value(active.inviteCode()));

        var other = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        mvc.perform(post(PATH).header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content(bind(uid, other.inviteCode())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/merchant-already-bound"));

        MerchantStoresFixture.setStatus(jdbc, active.id(), "DISABLED");
        mvc.perform(get(PATH).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bound").value(true));
        assertThat(jdbc.queryForObject("SELECT merchant_store_id FROM user_merchant_bindings WHERE user_id = ?",
                Long.class, uid)).isEqualTo(active.id());
    }

    @Test
    @DisplayName("兽医 token 不能填码（403）；游客 401")
    void userRoleOnly() throws Exception {
        long vetId = vets.newActiveVet("drmerchant").getId();
        mvc.perform(post(PATH).header("Authorization", vetBearer(vetId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000035\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("下单写当时的绑定门店（门店已停用也照写）；付款写 paid_at；签收写 return_window_ends_at = 签收 + 7 天")
    void orderCarriesReferrerAndContractTimestamps() throws Exception {
        long uid = newUser().getId();
        var store = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        mvc.perform(post(PATH).header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content(bind(uid, store.inviteCode())))
                .andExpect(status().isOk());
        MerchantStoresFixture.setStatus(jdbc, store.id(), "DISABLED");

        long n = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'P', 'B', 'MAKANAN', 'k.jpg', 'DOG', '<p/>', 'n', 'RETURNABLE', true)""", "mbp" + n);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, "mbp" + n);
        jdbc.update("INSERT INTO shop_skus (public_token, product_id, spec_name, price) VALUES (?, ?, '1 kg', 50000)",
                "mbs" + n, pid);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, "mbs" + n);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 5, 0)", sid);
        String kec = "MBk" + n;
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 10_000L, 1L);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();

        carts.add(uid, "mbs" + n, 1, "TOKO_ALL_FEATURED", null);
        ShopOrder order = checkout.placeOrder(uid, addr, null, null);
        assertThat(order.getReferrerMerchantId()).isEqualTo(store.id());
        assertThat(order.getPaidAt()).isNull();

        var pay = payments.pay(uid, order.getPublicToken(), null);
        PaymentIntent intent = paymentIntents.findByToken(pay.paymentIntentToken()).orElseThrow();
        paymentIntents.applyCallback(new PaymentCallback(intent.getPublicToken(), "gw-mb-" + n,
                GatewayStatus.PAID, Map.of()));
        ShopOrder paid = orders.findByPublicToken(order.getPublicToken()).orElseThrow();
        assertThat(paid.getPaidAt()).isNotNull();

        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "TRK" + n, 0L);
        fulfillment.markDeliveredByAdmin(order.getPublicToken());
        ShopOrder delivered = orders.findByPublicToken(order.getPublicToken()).orElseThrow();
        var row = jdbc.queryForMap("SELECT delivered_at, return_window_ends_at, referrer_merchant_id, paid_at "
                + "FROM shop_orders WHERE id = ?", delivered.getId());
        java.time.Instant deliveredAt = ((java.sql.Timestamp) row.get("delivered_at")).toInstant();
        java.time.Instant windowEnds = ((java.sql.Timestamp) row.get("return_window_ends_at")).toInstant();
        assertThat(windowEnds).isEqualTo(deliveredAt.plus(Duration.ofDays(7)));
        assertThat(row.get("referrer_merchant_id")).isEqualTo(store.id());
        assertThat(row.get("paid_at")).isNotNull();
    }
}
