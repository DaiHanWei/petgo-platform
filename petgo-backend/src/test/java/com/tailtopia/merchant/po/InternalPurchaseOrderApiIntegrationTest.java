package com.tailtopia.merchant.po;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.tailtopia.merchant.po.service.PurchaseOrderFulfillmentService;
import com.tailtopia.merchant.po.service.PurchaseOrderScanner;
import com.tailtopia.merchant.support.MerchantStoresFixture;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1：进货内部接口全链路（契约第 5 节）—— 建单（幂等 / 价格复核 / 库存 / 门店状态）→ 发起支付 → 到账 →
 * ops 发货 / 送达 → 列表 / 汇总 / 按幂等键查；超时惰性取消；门店隔离。需 Docker postgres + redis。
 *
 * <p>独立端口与密钥来自 {@code ApiIntegrationTest} 基类属性（18095 / test-internal-secret）。
 */
class InternalPurchaseOrderApiIntegrationTest extends ApiIntegrationTest {

    private static final int PORT = 18095;
    private static final String SECRET = "test-internal-secret";
    private static final String BASE = "/internal/merchant/purchase-orders";
    private static final char[] B62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PurchaseOrderFulfillmentService fulfillment;
    @Autowired
    private PurchaseOrderScanner scanner;

    private static String key() {
        StringBuilder sb = new StringBuilder(22);
        for (int i = 0; i < 22; i++) {
            sb.append(B62[RND.nextInt(B62.length)]);
        }
        return sb.toString();
    }

    private MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder b, long storeId) {
        return b.with(r -> {
            r.setLocalPort(PORT);
            return r;
        }).header("X-Internal-Secret", SECRET).header("X-Merchant-Store-Id", String.valueOf(storeId))
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
    }

    /** 建一个上架商品 + SKU（带批发价）+ 库存，返回 sku token。 */
    private String seedSku(Long wholesale, long stock) {
        long n = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Royal Canin', 'B', 'MAKANAN', 'shop/rc.jpg', 'DOG', '<p/>', 'n', 'RETURNABLE', true)""",
                "pop" + n);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, "pop" + n);
        jdbc.update("INSERT INTO shop_skus (public_token, product_id, spec_name, price, wholesale_price) "
                + "VALUES (?, ?, '3 kg', 285000, ?)", "pos" + n, pid, wholesale);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, "pos" + n);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)", sid, stock);
        return "pos" + n;
    }

    private long locked(String skuToken) {
        return jdbc.queryForObject("SELECT i.locked FROM sku_inventory i JOIN shop_skus s ON s.id = i.sku_id "
                + "WHERE s.public_token = ?", Long.class, skuToken);
    }

    private long actual(String skuToken) {
        return jdbc.queryForObject("SELECT i.actual FROM sku_inventory i JOIN shop_skus s ON s.id = i.sku_id "
                + "WHERE s.public_token = ?", Long.class, skuToken);
    }

    private static String body(String skuToken, int qty, long price) {
        return """
                {"lines":[{"sku_token":"%s","qty":%d,"wholesale_price":%d}],
                 "shipping_address":{"receiver_name":"Budi","receiver_phone":"+6281234567890",
                   "provinsi":"DKI Jakarta","kota_kabupaten":"Jakarta Selatan","kecamatan":"Kebayoran Baru",
                   "address_line":"Jl. Melati 5","kode_pos":"12110"},
                 "shipping_method":"COD_SHIPPING",
                 "store_snapshot":{"name":"Toko Budi","phone":"+6281234567890"},
                 "unknown_field":"ignored"}""".formatted(skuToken, qty, price);
    }

    private MvcResult create(long storeId, String key, String body) throws Exception {
        return mvc.perform(internal(post(BASE), storeId).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode jsonOf(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("🔗 建单 201 → 同 key 重放 200 同单 → 改体 409；付款 → 到账扣库存 → 发货 → 送达 → 列表 / 汇总")
    void happyPath() throws Exception {
        var store = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        String sku = seedSku(200_000L, 10);
        String key = key();

        MvcResult created = create(store.id(), key, body(sku, 3, 200_000L));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode po = jsonOf(created);
        String token = po.get("po_token").asString();
        assertThat(po.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(po.get("goods_amount").asLong()).isEqualTo(600_000L);
        assertThat(po.get("order_no").asString()).startsWith("PO-");
        assertThat(po.get("lines").get(0).get("product_name").asString()).isEqualTo("Royal Canin");
        assertThat(po.get("lines").get(0).get("image_key").asString()).isEqualTo("shop/rc.jpg");
        assertThat(po.get("lines").get(0).get("subtotal").asLong()).isEqualTo(600_000L);
        assertThat(po.get("shipments")).isEmpty();
        assertThat(po.has("id")).isFalse();
        assertThat(locked(sku)).isEqualTo(3L);

        MvcResult replay = create(store.id(), key, body(sku, 3, 200_000L));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(jsonOf(replay).get("po_token").asString()).isEqualTo(token);
        assertThat(locked(sku)).as("重放不重复锁库存").isEqualTo(3L);

        MvcResult conflict = create(store.id(), key, body(sku, 2, 200_000L));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(jsonOf(conflict).get("type").asString())
                .isEqualTo("urn:tailtopia:internal:merchant:idempotency-conflict");

        mvc.perform(internal(get(BASE + "/by-idempotency-key/" + key), store.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.po_token").value(token));

        // 发起支付：天然幂等，两次同一个码
        MvcResult pay1 = mvc.perform(internal(post(BASE + "/" + token + "/pay"), store.id())).andReturn();
        assertThat(pay1.getResponse().getStatus()).isEqualTo(200);
        JsonNode p1 = jsonOf(pay1);
        assertThat(p1.get("po_token").asString()).isEqualTo(token);
        assertThat(p1.get("amount").asLong()).isEqualTo(600_000L);
        assertThat(p1.get("qris_content").asString()).isNotBlank();
        assertThat(Instant.parse(p1.get("expires_at").asString()))
                .isBeforeOrEqualTo(Instant.parse(po.get("payment_deadline").asString()));
        JsonNode p2 = jsonOf(mvc.perform(internal(post(BASE + "/" + token + "/pay"), store.id())).andReturn());
        assertThat(p2.get("qris_content").asString()).isEqualTo(p1.get("qris_content").asString());

        // 到账（门店停用不阻止到账，FR-40）
        MerchantStoresFixture.setStatus(jdbc, store.id(), "DISABLED");
        String intentToken = jdbc.queryForObject("SELECT payment_intent_token FROM purchase_orders WHERE public_token = ?",
                String.class, token);
        assertThat(jdbc.queryForObject("SELECT user_id FROM payment_intents WHERE public_token = ?", Long.class,
                intentToken)).as("付款人是门店：-store_id").isEqualTo(-store.id());
        paymentIntents.applyCallback(new PaymentCallback(intentToken, "gw-po-" + SEQ.incrementAndGet(),
                GatewayStatus.PAID, Map.of()));
        MerchantStoresFixture.setStatus(jdbc, store.id(), "ACTIVE");
        JsonNode paid = jsonOf(mvc.perform(internal(get(BASE + "/" + token), store.id())).andReturn());
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("paid_at").asString()).isNotBlank();
        assertThat(locked(sku)).isZero();
        assertThat(actual(sku)).isEqualTo(7L);

        // 已付款再付 → not-payable
        mvc.perform(internal(post(BASE + "/" + token + "/pay"), store.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:tailtopia:internal:merchant:not-payable"))
                .andExpect(jsonPath("$.po_status").value("PAID"));

        // ops 发货两包、标记送达
        fulfillment.addShipment(token, "JNE REG", "JNE1", "https://jne.co.id/t/JNE1", 1L);
        fulfillment.addShipment(token, "SiCepat", "SC2", null, 1L);
        fulfillment.markDelivered(token, 1L);
        JsonNode delivered = jsonOf(mvc.perform(internal(get(BASE + "/" + token), store.id())).andReturn());
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.get("shipments")).hasSize(2);
        assertThat(delivered.get("shipments").get(0).get("carrier").asString()).isEqualTo("JNE REG");

        // 列表 / 汇总（只含本店）
        var otherStore = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        create(otherStore.id(), key(), body(seedSku(1L, 5), 1, 1L));
        mvc.perform(internal(get(BASE).param("size", "100"), store.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].po_token").value(token))
                .andExpect(jsonPath("$.items[0].first_line.product_name").value("Royal Canin"))
                .andExpect(jsonPath("$.items[0].line_count").value(1))
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.total_pages").value(1));
        mvc.perform(internal(get(BASE).param("page", "5"), store.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.page").value(5)).andExpect(jsonPath("$.total_elements").value(1));
        mvc.perform(internal(get(BASE + "/summary"), store.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_count").value(1))
                .andExpect(jsonPath("$.paid_goods_amount").value(600_000));

        // 跨店读 → 404
        mvc.perform(internal(get(BASE + "/" + token), otherStore.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:tailtopia:internal:merchant:not-found"));
    }

    @Test
    @DisplayName("价格复核一次列全（不存在 / 无批发价 → 显式 null）；缺货一次列全；被拒不写幂等，同 key 可再试")
    void rejections() throws Exception {
        var store = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        String priced = seedSku(100_000L, 1);
        String unpriced = seedSku(null, 10);
        String key = key();
        String twoLines = body(priced, 1, 90_000L).replace(
                "{\"sku_token\":\"" + priced + "\",\"qty\":1,\"wholesale_price\":90000}",
                "{\"sku_token\":\"" + priced + "\",\"qty\":1,\"wholesale_price\":90000},"
                        + "{\"sku_token\":\"" + unpriced + "\",\"qty\":1,\"wholesale_price\":5},"
                        + "{\"sku_token\":\"nosuchsku\",\"qty\":1,\"wholesale_price\":5}");
        MvcResult r = create(store.id(), key, twoLines);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode p = jsonOf(r);
        assertThat(p.get("type").asString()).isEqualTo("urn:tailtopia:internal:merchant:price-changed");
        assertThat(p.get("changed_skus")).hasSize(3);
        assertThat(p.get("changed_skus").get(0).get("wholesale_price").asLong()).isEqualTo(100_000L);
        assertThat(p.get("changed_skus").get(1).has("wholesale_price")).as("显式 null").isTrue();
        assertThat(p.get("changed_skus").get(1).get("wholesale_price").isNull()).isTrue();
        assertThat(p.get("changed_skus").get(2).get("wholesale_price").isNull()).isTrue();

        MvcResult oos = create(store.id(), key, body(priced, 2, 100_000L));
        assertThat(oos.getResponse().getStatus()).isEqualTo(409);
        assertThat(jsonOf(oos).get("type").asString()).isEqualTo("urn:tailtopia:internal:merchant:out-of-stock");
        assertThat(jsonOf(oos).get("out_of_stock_skus").get(0).asString()).isEqualTo(priced);

        MerchantStoresFixture.setStatus(jdbc, store.id(), "DISABLED");
        MvcResult disabled = create(store.id(), key, body(priced, 1, 100_000L));
        assertThat(disabled.getResponse().getStatus()).isEqualTo(403);
        assertThat(jsonOf(disabled).get("type").asString()).isEqualTo("urn:tailtopia:internal:merchant:store-disabled");

        MerchantStoresFixture.setStatus(jdbc, store.id(), "ACTIVE");
        assertThat(create(store.id(), key, body(priced, 1, 100_000L)).getResponse().getStatus())
                .as("前几次被拒都没写幂等记录").isEqualTo(201);
    }

    @Test
    @DisplayName("请求头：密钥错 401、带 CF-Connecting-IP 403、门店头非法 400、门店不存在 404、Idempotency-Key 非法 400")
    void headers() throws Exception {
        var store = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        mvc.perform(get(BASE).with(r -> {
            r.setLocalPort(PORT);
            return r;
        }).header("X-Internal-Secret", "wrong").header("X-Merchant-Store-Id", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(internal(get(BASE), store.id()).header("CF-Connecting-IP", "1.1.1.1"))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/summary").with(r -> {
            r.setLocalPort(PORT);
            return r;
        }).header("X-Internal-Secret", SECRET).header("X-Merchant-Store-Id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:tailtopia:internal:merchant:invalid-request"));
        mvc.perform(internal(get(BASE + "/summary"), 9_000_000_000L))
                .andExpect(status().isNotFound());
        mvc.perform(internal(post(BASE), store.id()).header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON).content(body("x", 1, 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(internal(get(BASE).param("status", "WHATEVER"), store.id()))
                .andExpect(status().isBadRequest());
        // 主端口上不存在
        mvc.perform(get(BASE).header("X-Internal-Secret", SECRET).header("X-Merchant-Store-Id",
                String.valueOf(store.id()))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("过付款截止：读即取消并释放锁定，cancelled_at = deadline；再付 → not-payable(CANCELLED)；扫描兜底")
    void lazyExpiry() throws Exception {
        var store = MerchantStoresFixture.insert(jdbc, "ACTIVE");
        String sku = seedSku(10_000L, 5);
        String token = jsonOf(create(store.id(), key(), body(sku, 2, 10_000L))).get("po_token").asString();
        mvc.perform(internal(post(BASE + "/" + token + "/pay"), store.id())).andExpect(status().isOk());
        assertThat(locked(sku)).isEqualTo(2L);

        jdbc.update("UPDATE purchase_orders SET payment_deadline = now() - interval '1 minute' WHERE public_token = ?",
                token);
        mvc.perform(internal(post(BASE + "/" + token + "/pay"), store.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.po_status").value("CANCELLED"));
        JsonNode cancelled = jsonOf(mvc.perform(internal(get(BASE + "/" + token), store.id())).andReturn());
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancel_reason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(cancelled.get("cancelled_at").asString()).isEqualTo(cancelled.get("payment_deadline").asString());
        assertThat(locked(sku)).isZero();
        String intentToken = jdbc.queryForObject("SELECT payment_intent_token FROM purchase_orders WHERE public_token = ?",
                String.class, token);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_token = ?", String.class,
                intentToken)).isEqualTo("FAILED");

        // 扫描兜底：无人查看的过期单
        String sku2 = seedSku(10_000L, 5);
        String token2 = jsonOf(create(store.id(), key(), body(sku2, 1, 10_000L))).get("po_token").asString();
        jdbc.update("UPDATE purchase_orders SET payment_deadline = now() - interval '1 minute' WHERE public_token = ?",
                token2);
        scanner.scanExpiry();
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE public_token = ?", String.class,
                token2)).isEqualTo("CANCELLED");
        assertThat(locked(sku2)).isZero();
    }
}
