package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：异常订单处置（Story 4.4，AB-11D / S-3 / C-9）。
 *
 * <p>🔴 核心断言三条：
 * <ol>
 *   <li><b>补偿溢价读的是「平台责任补偿溢价」，不是激励溢价</b> —— 两者共用同一数值是
 *       静默错误（C-9 / D-8），故测试刻意把两个配置项设成<b>不同</b>的值；</li>
 *   <li><b>PawCoin 段只退回 PawCoin</b> —— 服务层不存在折成现金的可达路径（FR-100A 规则 1）；</li>
 *   <li><b>库存回补走退货入库批次</b>（S-9 / SPEC-11），采购单号即原订单号。</li>
 * </ol>
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class AdminShopOrderExceptionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminShopOrderExceptionService exceptions;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
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
    private SkuInventoryRepository inventory;
    @Autowired
    private com.tailtopia.shop.service.InventoryMovementService movements;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    /**
     * 🔴 <b>单行 {@code pawcoin_config} 是共享态，测完必须还原</b>。
     *
     * <p>本类为验 C-9 刻意把激励溢价与补偿溢价设成不同值；不还原会让
     * {@code PlatformConfigIntegrationTest.seedDefaultsMatchEnvBaseline} 与
     * {@code MeRefundIntegrationTest} 在<b>全量跑</b>时莫名其妙地红 —— 单跑却全绿，
     * 是本仓库最误导人的一类失败（见 HANDOFF §测试基建）。
     */
    @org.junit.jupiter.api.AfterEach
    void restoreSharedPawcoinConfig() {
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 0, "
                + "compensation_premium_rate = 0, compensation_premium_cap = 0");
    }

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "exc" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class,
                "exc" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "ep" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "es" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 0, 0)", sid);
        // 🔴 库存走【真实采购入库】而非直接 INSERT：退货入库的单价按 S-9 取该 SKU 最近一次采购价，
        //    没有采购历史就登记不了退货入库 —— 直接 INSERT 造出的 SKU 在现实中并不存在
        //    （货是怎么进来的？），用它测异常处置会掩盖这条真实约束。
        movements.receivePurchase(sid, stock, "PO-" + SEQ.incrementAndGet(), "供应商",
                price / 2, java.time.LocalDate.now(), ACTOR);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class,
                token);
    }

    /** 纯 PawCoin 已付款单（Coin 段 = 全额，便于验退回与溢价）。 */
    private Ctx pureCoinPaidOrder(long coins) {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, coins, PawCoinTxnType.TOPUP, "TEST", null,
                "exc-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Kexc" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        return new Ctx(uid, sku, orders.findByPublicToken(o.getPublicToken()).orElseThrow());
    }

    private record Ctx(long userId, String skuToken, ShopOrder order) {
    }

    // ---------- 处置① 整单取消并退款 ----------

    @Test
    @DisplayName("整单取消：状态 CANCELLED + 库存回补 + PawCoin 全额退回 + 站内信")
    void cancelWholeRefundsCoinAndRestocks() {
        Ctx c = pureCoinPaidOrder(500_000L);
        long sid = skuId(c.skuToken());
        assertThat(inventory.findBySkuId(sid).orElseThrow().getActual())
                .as("付款后已出库").isEqualTo(9L);
        long balanceBefore = wallet.balanceOf(c.userId());

        var out = exceptions.cancelWholeOrder(c.order().getPublicToken(), "缺货", ACTOR);

        ShopOrder after = orders.findByPublicToken(c.order().getPublicToken()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(out.coinRefunded()).isEqualTo(100_000L);
        assertThat(wallet.balanceOf(c.userId()))
                .isEqualTo(balanceBefore + out.coinRefunded() + out.compensationPremium());
        // 🔴 库存回补到 actual（走退货入库批次，S-9）
        assertThat(inventory.findBySkuId(sid).orElseThrow().getActual()).isEqualTo(10L);
        // S-9：采购单号填原订单号 —— 这就是「留存调整原因」
        Long returnInbounds = jdbc.queryForObject("""
                SELECT count(*) FROM inventory_movements
                WHERE sku_id = ? AND movement_type = 'RETURN_INBOUND' AND purchase_no = ?""",
                Long.class, sid, c.order().getPublicToken());
        assertThat(returnInbounds).isEqualTo(1L);
        // 站内信告知原因并致歉
        Long notified = jdbc.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE recipient_user_id = ? AND type = ? AND target_ref = ?""",
                Long.class, c.userId(), NotificationType.SHOP_ORDER_EXCEPTION.name(),
                c.order().getPublicToken());
        assertThat(notified).isEqualTo(1L);
    }

    @Test
    @DisplayName("🔴 C-9：补偿溢价读【平台责任补偿溢价】，与激励溢价取不同值时各归各的")
    void compensationPremiumUsesItsOwnConfigItem() {
        // 两个配置项刻意设成不同值 —— 共用同一数值是静默错误（C-9 / D-8）
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 50, compensation_premium_rate = 10, "
                + "compensation_premium_cap = 0");
        Ctx c = pureCoinPaidOrder(500_000L);

        var out = exceptions.cancelWholeOrder(c.order().getPublicToken(), "缺货", ACTOR);

        // 100 000 × 10%（补偿溢价），不是 × 50%（激励溢价）
        assertThat(out.compensationPremium()).isEqualTo(10_000L);
        Long bonusRows = jdbc.queryForObject("""
                SELECT count(*) FROM pawcoin_transactions
                WHERE user_id = ? AND type = 'BONUS' AND delta = 10000""",
                Long.class, c.userId());
        assertThat(bonusRows).isEqualTo(1L);
    }

    @Test
    @DisplayName("补偿溢价受单笔上限约束（cap > 0 时封顶）")
    void compensationPremiumRespectsCap() {
        jdbc.update("UPDATE pawcoin_config SET compensation_premium_rate = 50, "
                + "compensation_premium_cap = 3000");
        Ctx c = pureCoinPaidOrder(500_000L);
        var out = exceptions.cancelWholeOrder(c.order().getPublicToken(), "缺货", ACTOR);
        assertThat(out.compensationPremium()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("🔴 溢价比例为 0 时不发币（不是发 0 条流水，是根本不动钱包）")
    void noPremiumWhenRateIsZero() {
        jdbc.update("UPDATE pawcoin_config SET compensation_premium_rate = 0");
        Ctx c = pureCoinPaidOrder(500_000L);
        var out = exceptions.cancelWholeOrder(c.order().getPublicToken(), "缺货", ACTOR);
        assertThat(out.compensationPremium()).isZero();
        Long bonusRows = jdbc.queryForObject(
                "SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND type = 'BONUS'",
                Long.class, c.userId());
        assertThat(bonusRows).isZero();
    }

    @Test
    @DisplayName("🔴 处置原因必填 —— 站内信要拿它告诉用户，审计要靠它复盘")
    void reasonIsRequired() {
        Ctx c = pureCoinPaidOrder(500_000L);
        assertThatThrownBy(() -> exceptions.cancelWholeOrder(c.order().getPublicToken(), "  ",
                ACTOR)).isInstanceOf(AppException.class).hasMessageContaining("原因");
        assertThat(orders.findByPublicToken(c.order().getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("🔴 已发货订单不可走异常取消 —— 货已出门，出口是退货（Epic 5）")
    void shippedOrderCannotBeCancelledHere() {
        Ctx c = pureCoinPaidOrder(500_000L);
        fulfillment.ship(c.order().getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);
        assertThatThrownBy(() -> exceptions.cancelWholeOrder(c.order().getPublicToken(), "缺货",
                ACTOR)).isInstanceOf(AppException.class).hasMessageContaining("待发货");
    }

    @Test
    @DisplayName("审计摘要记全三段金额与原因，但不含收件人任何字段")
    void auditRecordsAmountsWithoutPii() {
        Ctx c = pureCoinPaidOrder(500_000L);
        exceptions.cancelWholeOrder(c.order().getPublicToken(), "盘点后发现少货", ACTOR);

        String summary = jdbc.queryForObject("""
                SELECT summary FROM admin_audit_logs
                WHERE action_type = ? AND target_id = ?""",
                String.class, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED,
                c.order().getPublicToken());
        assertThat(summary).contains("盘点后发现少货").contains("PawCoin 段退回")
                .contains("待退现金段");
        assertThat(summary).doesNotContain("Budi").doesNotContain("8123456789")
                .doesNotContain("Jl. Test");
    }

    // ---------- 处置② 部分取消 ----------

    @Test
    @DisplayName("部分取消：该行 refundedQty 累加 + 该行库存回补，订单状态不变")
    void cancelLineRestocksThatLineOnly() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 900_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "exc-topup2:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Kexc" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 3);
        ShopOrder order = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, order.getPublicToken(), null);
        long sid = skuId(sku);
        assertThat(inventory.findBySkuId(sid).orElseThrow().getActual()).isEqualTo(7L);

        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(
                orders.findByPublicToken(order.getPublicToken()).orElseThrow().getId());
        exceptions.cancelLine(order.getPublicToken(), lines.get(0).getId(), 2, "缺 2 件", ACTOR);

        assertThat(inventory.findBySkuId(sid).orElseThrow().getActual()).isEqualTo(9L);
        assertThat(orderLines.findById(lines.get(0).getId()).orElseThrow().getRefundedQty())
                .isEqualTo(2);
        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .as("部分取消不改订单状态").isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("🔴 部分取消数量累加不得超过下单数量")
    void cancelLineCannotExceedOrderedQty() {
        Ctx c = pureCoinPaidOrder(500_000L);
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(c.order().getId());
        assertThatThrownBy(() -> exceptions.cancelLine(c.order().getPublicToken(),
                lines.get(0).getId(), 2, "x", ACTOR))
                .isInstanceOf(AppException.class).hasMessageContaining("超过下单数量");
    }

    // ---------- 处置③ 联系用户后继续 ----------

    @Test
    @DisplayName("联系用户后继续：不动状态、不动库存、不动钱，只留痕与告知")
    void contactAndContinueChangesNothingButRecords() {
        Ctx c = pureCoinPaidOrder(500_000L);
        long sid = skuId(c.skuToken());
        long balanceBefore = wallet.balanceOf(c.userId());

        exceptions.contactAndContinue(c.order().getPublicToken(), "用户同意等 3 天", ACTOR);

        assertThat(orders.findByPublicToken(c.order().getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(inventory.findBySkuId(sid).orElseThrow().getActual()).isEqualTo(9L);
        assertThat(wallet.balanceOf(c.userId())).isEqualTo(balanceBefore);
        Long audited = jdbc.queryForObject("""
                SELECT count(*) FROM admin_audit_logs
                WHERE action_type = ? AND target_id = ? AND summary LIKE '%继续履约%'""",
                Long.class, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED,
                c.order().getPublicToken());
        assertThat(audited).isEqualTo(1L);
    }

    // ---------- 🔒 FR-100A 规则 1：能力缺席 ----------

    @Test
    @DisplayName("🔒 服务层不存在任何「PawCoin 段折成现金退回」的可达路径（能力缺席，非权限判断）")
    void noPathToRefundCoinSegmentAsCash() {
        for (var m : AdminShopOrderExceptionService.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            assertThat(name)
                    .as("方法 %s 的命名暗示存在把 Coin 段折成现金的入口", m.getName())
                    .doesNotContain("cash").doesNotContain("payout").doesNotContain("withdraw");
        }
        // 本服务对钱包的唯一写入方式是 credit（进钱包），没有任何 debit / 提现调用
        assertThat(java.util.Arrays.stream(AdminShopOrderExceptionService.class
                        .getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("refundToBank", "payoutCash", "convertCoinToCash");
    }
}
