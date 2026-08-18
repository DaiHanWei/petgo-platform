package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.RepurchaseDashboardService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Story 9.2 归因链服务端闭合。
 *
 * <p>🔴 <b>为什么这条 story 存在</b>：原埋点清单只到 {@code add_to_cart} 为止 ——
 * <b>能算点击率，算不出转化率</b>。而 AB-13B 用「触发卡转化率 vs 普通商品曝光转化率」
 * 判定 A-16（复购引擎是否成立），是本版本核心论证的<b>唯一依据</b>。
 *
 * <p>✅ 服务端侧已由 Story 3.4 落地（{@code shop_order_lines} 持久化
 * {@code entry_source} / {@code trigger_type}）—— <b>本 story 只负责闭环核对</b>：
 * 一笔从触发卡进入的订单，其来源要在服务端一路可追溯到看板底账。
 *
 * <p>⚠️ <b>客户端那一份只是校验用</b>：客户端事件会被广告拦截与丢包吃掉，
 * 两套一比就知道端上丢了多少，<b>偏差过大以服务端为准</b>。
 *
 * <p>L-6 前车之鉴：V1.1.2 因埋点与改版同版本发布，三项核心指标不可得。同类错误不可重演。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class Epic9AttributionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private CartService carts;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private AdminShopPawcoinRulesService rules;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShopOrderLineRepository orderLines;
    @Autowired
    private RepurchaseDashboardService dashboard;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    // ---------- 造数（一律经真实服务，不手写 INSERT 业务行） ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "e9" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "e9" + n);
    }

    private String seedSku() {
        String pToken = "e9p" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "e9s" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', 100000)""", sToken, pid);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 100, 0)", sid);
        return sToken;
    }

    private long readyUser() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "e9-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        return uid;
    }

    private String addressFor(long uid) {
        String kec = "Ke9" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    // ---------- 断言 ----------

    @Test
    @DisplayName("🔴 从补货卡进来的一笔订单，其来源在服务端一路可追溯（加购 → 订单行 → 看板底账）")
    void triggerCardOrderIsTraceableEndToEnd() {
        // entry_source 落库 varchar(32)，测试用来源要短到放得下
        String uniqueSource = "T_CARD_" + SEQ.incrementAndGet();
        long uid = readyUser();
        String sku = seedSku();

        // 归因的起点在【加购那一刻】—— 那是唯一知道用户从哪进来的时刻
        carts.add(uid, sku, 1, uniqueSource, "FOOD_LOW");

        // 结算页要把它一并下发给端上（Story 9.2 新增），端上才能带上同一份值互为校验
        CheckoutPreviewView preview =
                CheckoutPreviewView.of(checkout.preview(uid, addressFor(uid)));
        assertThat(preview.lines()).hasSize(1);
        assertThat(preview.lines().get(0).entrySource()).isEqualTo(uniqueSource);
        assertThat(preview.lines().get(0).triggerType()).isEqualTo("FOOD_LOW");

        ShopOrder o = checkout.placeOrder(uid, addressFor(uid), null, null);
        payments.pay(uid, o.getPublicToken(), null);

        // 🔴 落到订单行 —— 这是权威口径，客户端事件丢了也不影响它
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(o.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getEntrySource()).isEqualTo(uniqueSource);
        assertThat(lines.get(0).getTriggerType()).isEqualTo("FOOD_LOW");

        // 🔴 看板底账数得到它 —— 对账的另一半（PostHog 侧同口径聚合）才有得比
        var snapshot = dashboard.snapshot(LocalDate.now().minusDays(1), LocalDate.now());
        assertThat(snapshot.linesByEntrySource()).containsEntry(uniqueSource, 1L);
    }

    @Test
    @DisplayName("整单归因来源下发给端上：同源取该源")
    void orderLevelAttributionIsSingleSourceWhenUniform() {
        String src = "T_UNI_" + SEQ.incrementAndGet();
        long uid = readyUser();
        carts.add(uid, seedSku(), 1, src, null);
        carts.add(uid, seedSku(), 1, src, null);
        ShopOrder o = checkout.placeOrder(uid, addressFor(uid), null, null);

        ShopOrderDetailView view = ShopOrderDetailView.of(
                orders.findById(o.getId()).orElseThrow(),
                orderLines.findByOrderIdOrderByIdAsc(o.getId()));
        assertThat(view.attributionSource()).isEqualTo(src);
    }

    @Test
    @DisplayName("🔴 一单多源标 mixed —— 挑第一条充数会让 AB-13B 的分子分母各错一次")
    void mixedSourcesAreNotCollapsedToTheFirstOne() {
        long uid = readyUser();
        carts.add(uid, seedSku(), 1, "T_A_" + SEQ.incrementAndGet(), null);
        carts.add(uid, seedSku(), 1, "T_B_" + SEQ.incrementAndGet(), null);
        ShopOrder o = checkout.placeOrder(uid, addressFor(uid), null, null);

        ShopOrderDetailView view = ShopOrderDetailView.of(
                orders.findById(o.getId()).orElseThrow(),
                orderLines.findByOrderIdOrderByIdAsc(o.getId()));
        assertThat(view.attributionSource()).isEqualTo("mixed");
    }

    @Test
    @DisplayName("无归因落 unknown，且在底账里进 (none) 档（不丢弃 —— 它是偏差的一部分）")
    void missingAttributionIsCountedNotDropped() {
        long uid = readyUser();
        carts.add(uid, seedSku(), 1);
        ShopOrder o = checkout.placeOrder(uid, addressFor(uid), null, null);

        ShopOrderDetailView view = ShopOrderDetailView.of(
                orders.findById(o.getId()).orElseThrow(),
                orderLines.findByOrderIdOrderByIdAsc(o.getId()));
        assertThat(view.attributionSource()).isEqualTo("unknown");

        var snapshot = dashboard.snapshot(LocalDate.now().minusDays(1), LocalDate.now());
        assertThat(snapshot.linesByEntrySource()).containsKey("(none)");
        assertThat(snapshot.linesByEntrySource().get("(none)")).isGreaterThanOrEqualTo(1L);
    }
}
