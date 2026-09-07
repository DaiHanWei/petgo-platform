package com.tailtopia.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.dto.OrderSummaryView;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：订单中心接入电商（Story 3.9，FR-101）。
 *
 * <p>🔴 本类同时看住**新增的第 5 类**与**既有 4 类没被碰坏** —— 后者是并行契约 O-1 的实质：
 * 改一个三线共享的 275 行聚合器，最大的风险不是新分支写错，而是顺手动了别人的分支。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class OrderCenterEcommerceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OrderCenterService orderCenter;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private CartService carts;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private AdminShopPawcoinRulesService rules;
    @Autowired
    private ConsultOrderRepository consultOrders;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long n() {
        return SEQ.incrementAndGet();
    }

    private String seedSku(long stock, long price, String name) {
        String pToken = "cp" + n();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, ?, 'B', 'MAKANAN', 'img/key.jpg', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken, name);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?",
                Long.class, pToken);
        String sToken = "cs" + n();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?",
                Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)", sid,
                stock);
        return sToken;
    }

    private ShopOrder placeShopOrder(long uid, Map<String, Integer> skuToQty) {
        rules.update(true, true, 1_000_000L, ACTOR);
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Kc" + n();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        skuToQty.forEach((sku, qty) -> carts.add(uid, sku, qty));
        return checkout.placeOrder(uid, addr, null, null);
    }

    private ConsultOrder seedVet(long userId, long amount) {
        ConsultOrder o = ConsultOrder.inProgress("ord-v-" + n(), userId, 1L, 1L, amount,
                PayChannel.QRIS, null, 30000, 60, 50000, Instant.now());
        o.markCompleted(Instant.now());
        return consultOrders.save(o);
    }

    private PaymentIntent seedTopup(long userId, long amount) {
        PaymentIntent i = PaymentIntent.create(userId, PaymentPurpose.PAWCOIN_TOPUP,
                PayChannel.QRIS, amount, "IDR", "ord-t-" + n());
        i.markPaid(Map.of());
        return intents.save(i);
    }

    private void setCreatedAt(String table, String tokenCol, String token, Instant at) {
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE " + tokenCol + " = ?",
                java.sql.Timestamp.from(at), token);
    }

    // ---------- 🔴 FR-101：第 5 类混排 ----------

    @Test
    @DisplayName("🔴 电商订单与虚拟商品订单同列表按时间倒序混排，不分栏")
    void ecommerceMixesWithVirtualOrders() {
        long uid = newUser().getId();
        Instant base = Instant.now();
        ConsultOrder vet = seedVet(uid, 50_000);
        PaymentIntent top = seedTopup(uid, 25_000);
        ShopOrder shop = placeShopOrder(uid, Map.of(seedSku(10, 285_000L, "Royal Canin"), 1));
        setCreatedAt("consult_orders", "order_token", vet.getOrderToken(),
                base.minus(30, ChronoUnit.SECONDS));
        setCreatedAt("payment_intents", "public_token", top.getPublicToken(),
                base.minus(20, ChronoUnit.SECONDS));
        setCreatedAt("shop_orders", "public_token", shop.getPublicToken(),
                base.minus(10, ChronoUnit.SECONDS));

        OrderPage page = orderCenter.listOrders(uid, null, null, 20, true);

        assertThat(page.items()).extracting(OrderSummaryView::orderType)
                .containsExactly("ECOMMERCE", "PAWCOIN_TOPUP", "VET_CONSULT");
    }

    @Test
    @DisplayName("筛选 ECOMMERCE 只回电商订单")
    void ecommerceTypeFilter() {
        long uid = newUser().getId();
        seedVet(uid, 50_000);
        seedTopup(uid, 25_000);
        placeShopOrder(uid, Map.of(seedSku(10, 100_000L, "Produk A"), 1));

        OrderPage page = orderCenter.listOrders(uid, "ECOMMERCE", null, 20, false);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().orderType()).isEqualTo("ECOMMERCE");
    }

    @Test
    @DisplayName("🔴 电商卡片带商品摘要：主图 + 「商品名 · 规格」+ 件数（件数非种类数）")
    void ecommerceCardCarriesItemSummary() {
        long uid = newUser().getId();
        // 两种商品共 3 件 —— 卡片上的数字必须是 3 而不是 2
        ShopOrder shop = placeShopOrder(uid, Map.of(
                seedSku(10, 100_000L, "Royal Canin"), 2,
                seedSku(10, 50_000L, "Drontal Plus"), 1));

        OrderSummaryView card = orderCenter.listOrders(uid, "ECOMMERCE", null, 20, false).items()
                .getFirst();

        assertThat(card.itemCount()).isEqualTo(3);
        // 「商品名 · 规格」——一列订单卡若只有类型和金额，用户找不到自己要的那一单
        assertThat(card.itemTitle()).contains("·");
        assertThat(card.itemTitle()).containsAnyOf("Royal Canin", "Drontal Plus");
        // 🔴 缩略图走 Story 1.6 的既定降级：CDN base 未配置（测试环境即如此）→ null，
        //    前端走占位图。**绝不返回半截 URL** —— 那会让客户端拿相对路径打自己的域名，
        //    表现为一堆 404 而不是「没有图」。
        assertThat(card.thumbnailUrl()).satisfiesAnyOf(
                url -> assertThat(url).isNull(),
                url -> assertThat(url).startsWith("http"));
    }

    @Test
    @DisplayName("待支付电商订单 → WARN（有事要做）；已取消 → 不用红色（取消不是错误）")
    void ecommerceStatusColors() {
        long uid = newUser().getId();
        ShopOrder shop = placeShopOrder(uid, Map.of(seedSku(10, 100_000L, "A"), 1));

        OrderSummaryView pending = orderCenter.listOrders(uid, "ECOMMERCE", null, 20, false).items()
                .getFirst();
        assertThat(pending.statusCode()).isEqualTo("PENDING_PAYMENT");
        assertThat(pending.statusColor()).isEqualTo("WARN");

        jdbc.update("UPDATE shop_orders SET status = 'CANCELLED' WHERE public_token = ?",
                shop.getPublicToken());
        OrderSummaryView cancelled = orderCenter.listOrders(uid, "ECOMMERCE", null, 20, false).items()
                .getFirst();
        assertThat(cancelled.statusColor()).isNotEqualTo("ERROR");
    }

    @Test
    @DisplayName("展示订单号前缀与虚拟商品隔离（TOKO-…，财务要能区分自营实物收入）")
    void displayNoPrefixIsolated() {
        long uid = newUser().getId();
        placeShopOrder(uid, Map.of(seedSku(10, 100_000L, "A"), 1));

        assertThat(orderCenter.listOrders(uid, "ECOMMERCE", null, 20, false).items().getFirst()
                .displayNo()).startsWith("TOKO-");
    }

    // ---------- 🔴 契约 O-1：既有 4 类一行未变 ----------

    @Test
    @DisplayName("🔴 既有类型的卡片新增字段恒为 null —— 它们本就没有商品，不是「暂时为空」")
    void existingTypesKeepNullItemFields() {
        long uid = newUser().getId();
        seedVet(uid, 50_000);
        seedTopup(uid, 25_000);

        assertThat(orderCenter.listOrders(uid, null, null, 20, true).items()).allSatisfy(v -> {
            assertThat(v.thumbnailUrl()).isNull();
            assertThat(v.itemTitle()).isNull();
            assertThat(v.itemCount()).isNull();
        });
    }

    @Test
    @DisplayName("🔴 ID_HD 仍然无源（javadoc 明写；不要顺手补）")
    void idHdStillHasNoSource() {
        long uid = newUser().getId();
        seedVet(uid, 50_000);
        placeShopOrder(uid, Map.of(seedSku(10, 100_000L, "A"), 1));

        assertThat(orderCenter.listOrders(uid, "ID_HD", null, 20, false).items()).isEmpty();
    }

    // ---------- 详情 ----------

    @Test
    @DisplayName("详情按 token 命中电商订单；越权与不存在同为 404")
    void detailResolvesEcommerceAndBlocksOthers() {
        long owner = newUser().getId();
        long stranger = newUser().getId();
        ShopOrder shop = placeShopOrder(owner, Map.of(seedSku(10, 100_000L, "A"), 1));

        var detail = orderCenter.getDetail(owner, shop.getPublicToken());
        assertThat(detail.orderType()).isEqualTo("ECOMMERCE");
        assertThat(detail.orderToken()).isEqualTo(shop.getPublicToken());

        assertThatThrownBy(() -> orderCenter.getDetail(stranger, shop.getPublicToken()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("订单不存在");
    }
}
