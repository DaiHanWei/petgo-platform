package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminReturnService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
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
import com.tailtopia.shop.returns.domain.CashDestination;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnType;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Epic 5 全链路联调（Story 5.10）。
 *
 * <p>🔴 <b>本类是 AD-2 的最终验收。</b>它走一笔<b>混合支付的三行订单</b>：
 * 先退 1 行、再退剩下 2 行，逐段检查
 * <ol>
 *   <li>部分退时<b>订单主状态不变</b>、<b>去程运费未退</b>；</li>
 *   <li>退完时<b>订单才转 REFUNDED</b>、<b>去程运费已退</b>；</li>
 *   <li>🔴 <b>累计退回的 PawCoin 恰好等于 coin_amount，零漂移</b>。</li>
 * </ol>
 * 前面每条 story 各自绿灯都证明不了第 3 条 —— 漂移只在<b>多次部分退款累计之后</b>才显形。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class Epic5ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CartService carts;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private ReturnRequestService returnRequests;
    @Autowired
    private ReturnRequestRepository returns;
    @Autowired
    private AdminReturnService adminReturns;
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
    private InventoryMovementService movements;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;
    private static final long ADMIN = 1L;

    /** 🔴 单行 pawcoin_config 是共享态，测完必须还原。 */
    @AfterEach
    void restoreSharedPawcoinConfig() {
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 0, "
                + "compensation_premium_rate = 0, compensation_premium_cap = 0");
    }

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "e5" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "e5" + n);
    }

    /** 🔴 库存走真实采购入库 —— 退货入库要按 S-9 取最近一次采购价。 */
    private String seedSku(long stock, long price) {
        String pToken = "e5p" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "e5s" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 0, 0)", sid);
        movements.receivePurchase(sid, stock, "PO-" + SEQ.incrementAndGet(), "供应商",
                price / 2, LocalDate.now(), ACTOR);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class,
                token);
    }

    /** 混合支付、已签收的三行订单，运费 20.000（免运门槛设高，让运费真实产生）。 */
    private Ctx mixedThreeLineDeliveredOrder() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        // Coin 只够抵一部分 → 走混合支付；金额刻意除不尽，让漂移有机会显形
        wallet.credit(uid, 60_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "e5-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(99_000_000L, ACTOR);
        String kec = "Ke5" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 20_000L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        List<String> skus = List.of(seedSku(10, 100_001L), seedSku(10, 89_999L),
                seedSku(10, 95_000L));
        for (String s : skus) {
            carts.add(uid, s, 1);
        }
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        var pay = payments.pay(uid, o.getPublicToken(), null);
        if (pay.paymentIntentToken() != null) {
            paymentIntents.applyCallback(new PaymentCallback(pay.paymentIntentToken(),
                    "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));
        }
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.markDeliveredByAdmin(o.getPublicToken());
        return new Ctx(uid, skus, orders.findByPublicToken(o.getPublicToken()).orElseThrow());
    }

    private record Ctx(long userId, List<String> skuTokens, ShopOrder order) {
    }

    private ShopOrder reload(String token) {
        return orders.findByPublicToken(token).orElseThrow();
    }

    /** 提交 → 批准 → 寄回 → 质检通过 → 退款执行，返回本次退回的 PawCoin。 */
    private long runReturnCycle(Ctx c, Map<Long, Integer> selection, ReturnType type) {
        ReturnRequest r = returnRequests.submit(c.userId(), c.order().getPublicToken(), type,
                selection, "note", type == ReturnType.QUALITY_ISSUE ? List.of("ev1") : null);
        r.chooseCashDestination(CashDestination.TO_BANK,
                com.tailtopia.pay.refund.domain.PayoutChannel.BCA, "1234567890", "Budi");
        returns.save(r);
        adminReturns.approve(r.getPublicToken(), ADMIN);
        adminReturns.registerShipback(r.getPublicToken(), "JNE", "SB" + SEQ.incrementAndGet(),
                12_000L, ADMIN);
        adminReturns.passInspection(r.getPublicToken(), "完好", null, ADMIN);
        return adminReturns.executeRefund(r.getPublicToken(), ADMIN).coinRefunded();
    }

    // ---------- 主链路 ----------

    @Test
    @DisplayName("🔴🔴 三行混合支付订单：先退 1 行再退 2 行 —— 主状态、去程运费、PawCoin 零漂移全对")
    void partialThenFullReturnChain() {
        Ctx c = mixedThreeLineDeliveredOrder();
        ShopOrder order = c.order();
        assertThat(order.getPayChannel()).isEqualTo(PayChannel.MIXED);
        long coinAmount = order.getCoinAmount();
        long balanceBefore = wallet.balanceOf(c.userId());
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        long sid0 = skuId(c.skuTokens().get(0));
        long stockBefore = inventory.findBySkuId(sid0).orElseThrow().getActual();

        // ===== 第一段：退 1/3 行 =====
        long coin1 = runReturnCycle(c, Map.of(lines.get(0).getId(), 1),
                ReturnType.NON_QUALITY_ISSUE);

        ShopOrder afterPartial = reload(order.getPublicToken());
        // 🔴 订单主状态不变（不被整单标记为已退款）
        assertThat(afterPartial.getStatus())
                .as("🔴 部分退款完成时订单主状态必须不变（AD-5）")
                .isEqualTo(ShopOrderStatus.DELIVERED);
        // 🔴 去程运费未退（部分退）
        assertThat(afterPartial.getRefundedTotal())
                .as("🔴 部分退不含去程运费 —— 这是堵凑单套利的闸门")
                .isEqualTo(lines.get(0).getLineTotal());
        // PawCoin 段按比例退回，且 pawcoin_transactions 有 REFUND 记录
        assertThat(coin1).isPositive();
        Long refundRows = jdbc.queryForObject(
                "SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND type = 'REFUND'",
                Long.class, c.userId());
        assertThat(refundRows).isGreaterThanOrEqualTo(1L);
        // 退货商品以退货批次入库、可售库存增加
        assertThat(inventory.findBySkuId(sid0).orElseThrow().getActual())
                .isEqualTo(stockBefore + 1);
        Long batch = jdbc.queryForObject("""
                SELECT count(*) FROM inventory_movements
                WHERE sku_id = ? AND movement_type = 'RETURN_INBOUND' AND purchase_no = ?""",
                Long.class, sid0, order.getPublicToken());
        assertThat(batch).isEqualTo(1L);

        // ===== 第二段：退完剩余 2 行 =====
        long coin2 = runReturnCycle(c,
                Map.of(lines.get(1).getId(), 1, lines.get(2).getId(), 1),
                ReturnType.NON_QUALITY_ISSUE);

        ShopOrder afterFull = reload(order.getPublicToken());
        // 🔴 全部行退完，订单此时才转 REFUNDED
        assertThat(afterFull.getStatus())
                .as("🔴 仅当全部行退完才回写 REFUNDED").isEqualTo(ShopOrderStatus.REFUNDED);
        // 🔴 整单退完时去程运费也已退回
        assertThat(afterFull.getRefundedTotal())
                .as("🔴 整单退完时去程运费也要退")
                .isEqualTo(order.getTotalAmount());
        // 🔴🔴 AD-2 核心验收：累计 PawCoin 恰好等于 coin_amount，零漂移
        assertThat(afterFull.getRefundedCoin())
                .as("🔴 少退是客诉，多退是防套现的缺口")
                .isEqualTo(coinAmount);
        assertThat(coin1 + coin2).isEqualTo(coinAmount);
        assertThat(wallet.balanceOf(c.userId())).isEqualTo(balanceBefore + coinAmount);
    }

    @Test
    @DisplayName("🔴 同订单进行中至多一张申请：第一张未结束时第二张被库级索引挡下")
    void onlyOneActiveRequestPerOrderAcrossTheChain() {
        Ctx c = mixedThreeLineDeliveredOrder();
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(c.order().getId());
        returnRequests.submit(c.userId(), c.order().getPublicToken(),
                ReturnType.NON_QUALITY_ISSUE, Map.of(lines.get(0).getId(), 1), "note", null);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> returnRequests.submit(c.userId(),
                        c.order().getPublicToken(), ReturnType.NON_QUALITY_ISSUE,
                        Map.of(lines.get(1).getId(), 1), "note", null))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class)
                .hasMessageContaining("进行中的退货申请");
    }

    @Test
    @DisplayName("🔴 质量问题：平台承担回程运费 + 自动发补偿溢价，CS 全程没经手金额")
    void qualityIssueGetsCompensationWithoutCsTouchingTheAmount() {
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 0, "
                + "compensation_premium_rate = 10, compensation_premium_cap = 0");
        Ctx c = mixedThreeLineDeliveredOrder();
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(c.order().getId());

        ReturnRequest r = returnRequests.submit(c.userId(), c.order().getPublicToken(),
                ReturnType.QUALITY_ISSUE, Map.of(lines.get(0).getId(), 1), "破损",
                List.of("ev1"));
        // 🔴 运费归属由类型自动得出，审核接口里根本没有这个参数
        assertThat(r.getReturnShipBearer())
                .isEqualTo(com.tailtopia.shop.returns.domain.ShippingFeeBearer.PLATFORM);
        r.chooseCashDestination(CashDestination.TO_BANK,
                com.tailtopia.pay.refund.domain.PayoutChannel.BCA, "1234567890", "Budi");
        returns.save(r);
        adminReturns.approve(r.getPublicToken(), ADMIN);
        adminReturns.registerShipback(r.getPublicToken(), "JNE", "SB" + SEQ.incrementAndGet(),
                12_000L, ADMIN);
        adminReturns.passInspection(r.getPublicToken(), "确认破损", null, ADMIN);

        var out = adminReturns.executeRefund(r.getPublicToken(), ADMIN);
        assertThat(out.compensationPremium()).isEqualTo(out.coinRefunded() * 10 / 100);
        // 平台承担 → 回程运费按用户上传的实际运单金额返还
        assertThat(out.shipbackReimbursed()).isEqualTo(12_000L);
    }

    // ---------- 🔒 防套现红线 ----------

    @Test
    @DisplayName("🔒🔒 不存在任何一条可达路径能把 PawCoin 段退成真钱（后台 / CS / API 全覆盖）")
    void noPathAnywhereTurnsCoinSegmentIntoCash() {
        // ① 退款执行服务：只有 credit，没有 debit / payout / withdraw
        String exec = readSource(
                "src/main/java/com/tailtopia/shop/returns/service/RefundExecutionService.java");
        String execCode = stripComments(exec);
        assertThat(execCode).contains("wallet.credit(");
        assertThat(execCode).doesNotContain("wallet.debit(");

        // ② 后台服务：不暴露任何金额入参 —— CS 连"改个数"的入口都没有
        for (var m : AdminReturnService.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertThat(n).doesNotContain("payout").doesNotContain("withdraw")
                    .doesNotContain("tocash");
        }
        String adminCode = stripComments(readSource(
                "src/main/java/com/tailtopia/admin/shop/service/AdminReturnService.java"));
        assertThat(adminCode)
                .as("🔴 后台若能直接动钱包，职责分离与防套现都形同虚设")
                .doesNotContain("wallet.");

        // ③ API 层：用户接口里【没有】coin destination 这个概念
        String apiCode = stripComments(readSource(
                "src/main/java/com/tailtopia/shop/returns/web/MeReturnController.java"));
        assertThat(apiCode).doesNotContain("coinDestination");
        // 现金段才有去向；PawCoin 段的枚举根本不存在
        assertThat(CashDestination.values()).hasSize(2);

        // ④ 数据模型：退款拆分只有两段，且两段之和恒等于总额（库级 CHECK 也守着）
        assertThat(com.tailtopia.shop.returns.domain.RefundSplit.class.getRecordComponents())
                .hasSize(2);
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static String readSource(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到源码：" + path, e);
        }
    }
}
