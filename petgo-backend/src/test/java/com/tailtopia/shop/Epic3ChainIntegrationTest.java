package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.dto.OrderSummaryView;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Epic 3 全链路联调（Story 3.10）。
 *
 * <p>🔴 <b>本类存在的理由：前九条 story 各自绿灯 ≠ 平台真的能卖出东西。</b>
 * 它走用户真实会走的那条路 —— <b>加购两个 SKU → 结算试算 → 下单（锁库存）→ 混合支付 →
 * 到账 → 订单出现在订单中心</b>，全程经真实 service，不用 JDBC 抄近路
 * （抄近路就测不到各段之间的接缝，而接缝正是本类要看的东西）。
 *
 * <p>同时看住两件<b>只有全链路才看得见</b>的事：
 * <ol>
 *   <li><b>结算页两段金额 == 实际扣款</b>：试算与下单必须走同一套计算，
 *       否则表现为「结算页显示要付 285.000，提交后变成 305.000」；</li>
 *   <li><b>归因链闭合</b>：加购时记的入口，一路走到订单行 —— AB-13B 判定 A-16 靠它。</li>
 * </ol>
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class Epic3ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CartService carts;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private AdminShopPawcoinRulesService rules;
    @Autowired
    private OrderCenterService orderCenter;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShopOrderLineRepository orderLines;
    @Autowired
    private SkuInventoryRepository inventory;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long n() {
        return SEQ.incrementAndGet();
    }

    private String seedSku(long stock, long price, String name) {
        String pToken = "e3p" + n();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, ?, 'B', 'MAKANAN', 'k.jpg', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken, name);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?",
                Long.class, pToken);
        String sToken = "e3s" + n();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?",
                Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)", sid,
                stock);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class,
                token);
    }

    private String seedAddress(long uid, long fee) {
        String kec = "E3k" + n();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", fee, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    @Test
    @DisplayName("🔗 加购 2 个 SKU → 结算 → 混合支付 → 到账：两段金额、账、货、订单四头对齐")
    void fullPurchaseChain() {
        long uid = newUser().getId();
        rules.update(true, true, 1_000_000L, ACTOR);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        String skuA = seedSku(10, 285_000L, "Royal Canin");
        String skuB = seedSku(10, 85_000L, "Drontal Plus");
        wallet.credit(uid, 60_000L, PawCoinTxnType.TOPUP, "TEST", null, "e3-topup:" + n());

        // ① 加购两件（带来源 —— 归因链起点）
        carts.add(uid, skuA, 1, "TOKO_ALL_FEATURED", null);
        carts.add(uid, skuB, 1, "TOKO_CATEGORY", null);

        // ② 结算试算：两段金额
        CheckoutPreviewView preview = CheckoutPreviewView.of(checkout.preview(uid, addr));
        assertThat(preview.goodsSubtotal()).isEqualTo(370_000L);
        assertThat(preview.payableTotal()).isEqualTo(390_000L);   // + 运费 20.000
        assertThat(preview.coinAmount()).isEqualTo(60_000L);
        assertThat(preview.cashAmount()).isEqualTo(330_000L);

        // ③ 下单：锁库存、不扣款
        ShopOrder order = checkout.placeOrder(uid, addr, null, null);
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        assertThat(order.getPayChannel()).isEqualTo(PayChannel.MIXED);
        // 🔴 结算页显示的两段金额 == 订单固化的两段金额（两处各算一遍必漂移）
        assertThat(order.getCoinAmount()).isEqualTo(preview.coinAmount());
        assertThat(order.getCashAmount()).isEqualTo(preview.cashAmount());
        assertThat(order.getTotalAmount()).isEqualTo(preview.payableTotal());
        assertThat(wallet.balanceOf(uid)).as("下单不扣款").isEqualTo(60_000L);
        assertThat(inventory.findBySkuId(skuId(skuA)).orElseThrow().getLocked()).isEqualTo(1L);

        // 🔴 归因链闭合：加购时记的入口一路走到订单行
        assertThat(orderLines.findByOrderIdOrderByIdAsc(order.getId()))
                .extracting(l -> l.getEntrySource())
                .containsExactlyInAnyOrder("TOKO_ALL_FEATURED", "TOKO_CATEGORY");

        // ④ 发起支付 → payment_intents 落 MIXED + 三列
        var pay = payments.pay(uid, order.getPublicToken(), null);
        PaymentIntent intent = paymentIntents.findByToken(pay.paymentIntentToken()).orElseThrow();
        assertThat(intent.getChannel()).isEqualTo(PayChannel.MIXED);
        assertThat(intent.getCoinAmount()).isEqualTo(60_000L);
        assertThat(intent.getCashAmount()).isEqualTo(330_000L);
        assertThat(intent.getCoinAmount() + intent.getCashAmount()).isEqualTo(intent.getAmount());
        assertThat(intent.getCoinRatio()).isNotNull();

        // ⑤ 到账
        paymentIntents.applyCallback(new PaymentCallback(intent.getPublicToken(), "gw-" + n(),
                GatewayStatus.PAID, Map.of()));

        // 账：PawCoin 精确减少 + 一条 SPEND 流水
        assertThat(wallet.balanceOf(uid)).isZero();
        Integer spends = jdbc.queryForObject(
                "SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND type = 'SPEND'",
                Integer.class, uid);
        assertThat(spends).isEqualTo(1);
        assertThat(paymentIntents.findByToken(intent.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);

        // 货：锁定转扣减
        var invA = inventory.findBySkuId(skuId(skuA)).orElseThrow();
        assertThat(invA.getActual()).isEqualTo(9L);
        assertThat(invA.getLocked()).isZero();

        // 订单：转待发货
        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);

        // ⑥ 订单中心：混排可见 + Belanja 能筛到
        OrderPage all = orderCenter.listOrders(uid, null, null, 20, true);
        assertThat(all.items()).extracting(OrderSummaryView::orderToken)
                .contains(order.getPublicToken());
        OrderPage belanja = orderCenter.listOrders(uid, "ECOMMERCE", null, 20, false);
        assertThat(belanja.items()).hasSize(1);
        assertThat(belanja.items().getFirst().itemCount()).isEqualTo(2);
        assertThat(belanja.items().getFirst().statusCode()).isEqualTo("PENDING_SHIPMENT");
    }

    @Test
    @DisplayName("🔴 归因链：同一 SKU 二次加购不覆盖首次来源（第二次不是转化发生的地方）")
    void reAddDoesNotOverwriteFirstAttribution() {
        long uid = newUser().getId();
        rules.update(true, true, 1_000_000L, ACTOR);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        String sku = seedSku(10, 100_000L, "A");

        carts.add(uid, sku, 1, "PROFILE_RECOMMEND", "REFILL");
        carts.add(uid, sku, 1, "TOKO_ALL_FEATURED", null);   // 用户在购物车里又加了一件

        ShopOrder order = checkout.placeOrder(uid, addr, null, null);

        var line = orderLines.findByOrderIdOrderByIdAsc(order.getId()).getFirst();
        assertThat(line.getEntrySource()).isEqualTo("PROFILE_RECOMMEND");
        assertThat(line.getTriggerType()).isEqualTo("REFILL");
        assertThat(line.getQty()).isEqualTo(2);
    }

    @Test
    @DisplayName("拿不到来源就写 NULL —— 诚实的「未知」好过编一个「从购物车结算」")
    void missingAttributionStaysNull() {
        long uid = newUser().getId();
        rules.update(true, true, 1_000_000L, ACTOR);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, seedSku(10, 100_000L, "A"), 1);

        ShopOrder order = checkout.placeOrder(uid, addr, null, null);

        assertThat(orderLines.findByOrderIdOrderByIdAsc(order.getId()).getFirst().getEntrySource())
                .isNull();
    }

    @Test
    @DisplayName("🔴 超服务范围：试算给可渲染的警示态，下单仍被阻断（两个动作不同口径是有意的）")
    void outOfRangePreviewsButBlocksOrder() {
        long uid = newUser().getId();
        rules.update(true, true, 1_000_000L, ACTOR);
        carts.add(uid, seedSku(10, 100_000L, "A"), 1);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789",
                "DKI Jakarta", "Jakarta Selatan", "Nowhere" + n(), "Jl. X", "12160", null))
                .getPublicToken();

        CheckoutPreviewView preview = CheckoutPreviewView.of(checkout.preview(uid, addr));
        assertThat(preview.serviceable()).isFalse();
        assertThat(preview.payableTotal()).isNull();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> checkout.placeOrder(uid, addr, null, null))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class)
                .hasMessageContaining("暂不配送至");
        // 阻断必须干净：一件库存都不该被锁
        assertThat(inventory.findBySkuId(skuId(carts.view(uid).lines().getFirst().skuToken()))
                .orElseThrow().getLocked()).isZero();
    }
}
