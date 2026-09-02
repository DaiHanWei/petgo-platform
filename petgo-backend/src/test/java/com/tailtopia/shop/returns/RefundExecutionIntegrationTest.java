package com.tailtopia.shop.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
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
import com.tailtopia.shop.returns.domain.CashDestination;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
import com.tailtopia.shop.returns.domain.ReturnType;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.returns.service.RefundExecutionService;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：🔴🔴 退款执行（Story 5.5，AB-12C，<b>安全攸关</b>）。
 *
 * <p>本类看的是<b>钱</b>：两段拆分是否精确、补偿溢价是否读对了配置项、重复执行会不会重复到账、
 * 以及最重要的一条 —— <b>有没有任何一条路能把 PawCoin 段退成真钱</b>（FR-100A 规则 1）。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class RefundExecutionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RefundExecutionService refunds;
    @Autowired
    private ReturnRequestService returnRequests;
    @Autowired
    private ReturnRequestRepository returns;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
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
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShopOrderLineRepository orderLines;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    /** 🔴 单行 pawcoin_config 是共享态，测完必须还原（否则全量跑会带红无关用例）。 */
    @AfterEach
    void restoreSharedPawcoinConfig() {
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 0, "
                + "compensation_premium_rate = 0, compensation_premium_cap = 0");
    }

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "re" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "re" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "rep" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "res" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                sid, stock);
        return sToken;
    }

    /**
     * 造一笔【混合支付、已签收】的三行订单。
     *
     * <p>混合支付是本类的默认场景 —— 两段拆分只有在两段都非零时才可能出错。
     */
    private Ctx mixedDeliveredOrder(long coinTopUp, long shippingFee, long... prices) {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, coinTopUp, PawCoinTxnType.TOPUP, "TEST", null,
                "re-topup:" + uid + ":" + SEQ.incrementAndGet());
        // 免运门槛设高，让运费真实产生（去程运费退不退是本 story 的一条 AC）
        zones.setFreeShippingThreshold(99_000_000L, ACTOR);
        String kec = "Kre" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", shippingFee, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        for (long p : prices) {
            carts.add(uid, seedSku(10, p), 1);
        }
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        var pay = payments.pay(uid, o.getPublicToken(), null);
        if (pay.paymentIntentToken() != null) {
            paymentIntents.applyCallback(new PaymentCallback(pay.paymentIntentToken(),
                    "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));
        }
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.markDeliveredByAdmin(o.getPublicToken());
        return new Ctx(uid, orders.findByPublicToken(o.getPublicToken()).orElseThrow());
    }

    private record Ctx(long userId, ShopOrder order) {
    }

    private ShopOrder reload(String token) {
        return orders.findByPublicToken(token).orElseThrow();
    }

    private List<ShopOrderLine> linesOf(ShopOrder o) {
        return orderLines.findByOrderIdOrderByIdAsc(o.getId());
    }

    /** 提交 → 批准 → 寄回 → 质检通过（把申请推到可执行退款的状态）。 */
    /**
     * 合法的凭证 key（2026-09-02，D-10）。
     *
     * <p>服务端现在校验两件事：**归属**（key 必须形如
     * {@code <keyPrefix>private/<userId>/…}，见 {@code MediaObjectKeys}）
     * 与**张数**（货在用户手上的退货要 ≥ 2 张，见 {@code ReturnRequestService.MIN_EVIDENCE}）。
     * 从前夹具里那种 {@code "ev1"} 两条都过不了。
     * ⚠️ 测试环境 {@code MEDIA_OSS_KEY_PREFIX} 为空，故前缀就是 {@code private/}。
     */
    private static java.util.List<String> evidence(long userId) {
        return java.util.List.of("private/" + userId + "/ev1.jpg", "private/" + userId + "/ev2.jpg");
    }

    private ReturnRequest readyForRefund(Ctx c, ReturnType type, Map<Long, Integer> selection,
            CashDestination dest) {
        ReturnRequest r = returnRequests.submit(c.userId(), c.order().getPublicToken(), type,
                selection, "note",
                type.isUndelivered() ? null : evidence(c.userId()));
        // TO_BANK 需要渠道与账号（渠道费由后端按 PayoutChannel 权威计算，前端不得传费）
        r.chooseCashDestination(dest,
                dest == CashDestination.TO_BANK
                        ? com.tailtopia.pay.refund.domain.PayoutChannel.BCA : null,
                dest == CashDestination.TO_BANK ? "1234567890" : null,
                dest == CashDestination.TO_BANK ? "Budi Santoso" : null);
        r.approve(ACTOR);
        returns.save(r);
        if (r.getStatus() == ReturnStatus.AWAIT_SHIPBACK) {
            r.registerShipback("JNE", "SB" + SEQ.incrementAndGet(), 12_000L);
            r.passInspection("完好", null);
            returns.save(r);
        }
        return returns.findByPublicToken(r.getPublicToken()).orElseThrow();
    }

    // ---------- 两段拆分 ----------

    @Test
    @DisplayName("🔴 部分退（1/3 行）：订单主状态不变，PawCoin 段按累计法退回，去程运费未退")
    void partialRefundKeepsOrderStatusAndSkipsOutboundFee() {
        Ctx c = mixedDeliveredOrder(60_000L, 20_000L, 100_000L, 90_000L, 95_000L);
        ShopOrder order = c.order();
        assertThat(order.getPayChannel()).isEqualTo(PayChannel.MIXED);
        long balanceBefore = wallet.balanceOf(c.userId());

        var lines = linesOf(order);
        ReturnRequest r = readyForRefund(c, ReturnType.NON_QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_BANK);
        var out = refunds.execute(r.getPublicToken());

        // 🔴 订单主状态不变
        assertThat(reload(order.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(out.orderFullyRefunded()).isFalse();
        // 退的是该行商品金额，去程运费不在其中
        assertThat(out.coinRefunded() + out.cashRefunded()).isEqualTo(100_000L);
        // PawCoin 段真的进了钱包（现金段走银行，不进钱包）
        assertThat(wallet.balanceOf(c.userId())).isEqualTo(balanceBefore + out.coinRefunded());
        Long refundRows = jdbc.queryForObject(
                "SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND type = 'REFUND'",
                Long.class, c.userId());
        assertThat(refundRows).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("🔴🔴 AD-2 核心验收：三行分三次退完 → 累计 PawCoin 恰好等于 coinAmount，零漂移")
    void refundingAllLinesInThreeStepsDriftsToZero() {
        Ctx c = mixedDeliveredOrder(60_000L, 20_000L, 100_001L, 89_999L, 95_000L);
        ShopOrder order = c.order();
        long coinAmount = order.getCoinAmount();
        assertThat(coinAmount).isPositive();
        long balanceBefore = wallet.balanceOf(c.userId());

        var lines = linesOf(order);
        long coinSum = 0;
        for (int i = 0; i < lines.size(); i++) {
            ReturnRequest r = readyForRefund(c,
                    i == lines.size() - 1 ? ReturnType.NON_QUALITY_ISSUE
                            : ReturnType.NON_QUALITY_ISSUE,
                    Map.of(lines.get(i).getId(), 1), CashDestination.TO_BANK);
            var out = refunds.execute(r.getPublicToken());
            coinSum += out.coinRefunded();
        }

        ShopOrder after = reload(order.getPublicToken());
        // 🔴 全部行退完 → 订单此时才转 REFUNDED
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.REFUNDED);
        // 🔴 零漂移
        assertThat(after.getRefundedCoin())
                .as("🔴 少退是客诉，多退是防套现的缺口").isEqualTo(coinAmount);
        assertThat(coinSum).isEqualTo(coinAmount);
        assertThat(wallet.balanceOf(c.userId())).isEqualTo(balanceBefore + coinAmount);
    }

    @Test
    @DisplayName("🔴 整单退：去程运费一并退回（部分退则不退）")
    void fullReturnRefundsOutboundFee() {
        Ctx c = mixedDeliveredOrder(60_000L, 20_000L, 100_000L);
        var lines = linesOf(c.order());
        ReturnRequest r = readyForRefund(c, ReturnType.NON_QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_BANK);
        assertThat(r.isFullReturn()).isTrue();

        var quote = refunds.quote(r.getPublicToken());
        assertThat(quote.outboundFeeRefund()).isEqualTo(20_000L);
        assertThat(quote.refundTotal()).isEqualTo(120_000L);
    }

    // ---------- 溢价（C-9 / D-8） ----------

    @Test
    @DisplayName("🔴 C-9：质量问题 → 补偿溢价读【平台责任补偿溢价】，不是激励溢价")
    void compensationPremiumUsesItsOwnConfig() {
        // 两个配置项刻意不同 —— 共用同一数值是静默错误
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 50, compensation_premium_rate = 10, "
                + "compensation_premium_cap = 0");
        Ctx c = mixedDeliveredOrder(60_000L, 0L, 100_000L);
        var lines = linesOf(c.order());
        ReturnRequest r = readyForRefund(c, ReturnType.QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_BANK);

        var out = refunds.execute(r.getPublicToken());

        // 补偿溢价的基数是 PawCoin 段，比例 10%（不是 50%）
        assertThat(out.compensationPremium()).isEqualTo(out.coinRefunded() * 10 / 100);
        assertThat(out.incentivePremium()).as("现金段退银行 → 不给激励溢价").isZero();
    }

    /**
     * 🔴 <b>本条 2026-09-02 反转过（D-16）</b>：原先断言「现金段转 PawCoin 就给激励溢价」，
     * 用的正是<b>已交付</b>的退货（{@code mixedDeliveredOrder} + {@code NON_QUALITY_ISSUE}）——
     * 那恰恰是套利口子本身：买 → 收货 → 退 → 转币白拿溢价。
     *
     * <p>{@code premium_rate} / {@code premium_fixed} 这对配置是<b>「未交付+转币」分支专用</b>的
     * 反套利激励（迁移 V20260817_2330 头注释 + PawCoinConfig 的 javadoc 两处写明），
     * 代码此前漏了这道门。现在补上：已交付的退货一律不给。
     *
     * <p>⚠️ 门本身的完整真值表在 L0 的 {@code RefundIncentiveGateTest}（含未交付那一支）——
     * 这里只钉住「有 DB 的真实链路上，已交付确实拿不到」。
     */
    @Test
    @DisplayName("🔴 D-16：已交付的退货转 PawCoin → **不给**激励溢价（C-1 反套利门）")
    void noIncentivePremiumForDeliveredReturns() {
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 5, compensation_premium_rate = 20, "
                + "compensation_premium_cap = 0");
        Ctx c = mixedDeliveredOrder(60_000L, 0L, 100_000L);
        var lines = linesOf(c.order());
        ReturnRequest r = readyForRefund(c, ReturnType.NON_QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_PAWCOIN);

        var out = refunds.execute(r.getPublicToken());

        assertThat(out.incentivePremium())
                .as("货到过用户手上；再给转币激励就是付钱请人「买 → 退 → 转币」")
                .isZero();
        assertThat(out.compensationPremium())
                .as("非质量问题不是平台责任 → 不给补偿溢价").isZero();
    }

    @Test
    @DisplayName("非平台责任不发补偿溢价（即便比例配得很高）")
    void noCompensationForNonPlatformFault() {
        jdbc.update("UPDATE pawcoin_config SET compensation_premium_rate = 50");
        Ctx c = mixedDeliveredOrder(60_000L, 0L, 100_000L);
        var lines = linesOf(c.order());
        ReturnRequest r = readyForRefund(c, ReturnType.NON_QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_BANK);

        assertThat(refunds.execute(r.getPublicToken()).compensationPremium()).isZero();
    }

    // ---------- 幂等与失败重试 ----------

    @Test
    @DisplayName("🔴 重复执行退款不重复到账（幂等键 shop-refund:{returnToken}）")
    void repeatedExecutionDoesNotDoubleCredit() {
        Ctx c = mixedDeliveredOrder(60_000L, 0L, 100_000L);
        var lines = linesOf(c.order());
        ReturnRequest r = readyForRefund(c, ReturnType.NON_QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_PAWCOIN);

        var first = refunds.execute(r.getPublicToken());
        long balanceAfterFirst = wallet.balanceOf(c.userId());
        var second = refunds.execute(r.getPublicToken());

        assertThat(second.coinRefunded()).isEqualTo(first.coinRefunded());
        assertThat(wallet.balanceOf(c.userId())).isEqualTo(balanceAfterFirst);
    }

    @Test
    @DisplayName("S-8 ③：退款失败可重试；超 3 次拒绝重试并要求转人工")
    void refundFailureIsRetryableUpToThreeTimes() {
        Ctx c = mixedDeliveredOrder(60_000L, 0L, 100_000L);
        var lines = linesOf(c.order());
        ReturnRequest r = readyForRefund(c, ReturnType.NON_QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_BANK);

        // 前两次失败仍可重试
        for (int i = 0; i < 2; i++) {
            refunds.markFailed(r.getPublicToken(), "网关超时");
            refunds.retry(r.getPublicToken());
        }
        // 第 3 次失败 → 达到上限，🔴 不再无限重试，转人工
        refunds.markFailed(r.getPublicToken(), "网关超时");
        assertThatThrownBy(() -> refunds.retry(r.getPublicToken()))
                .isInstanceOf(AppException.class).hasMessageContaining("转人工");
    }

    @Test
    @DisplayName("非 REFUNDING 状态不可执行退款")
    void cannotExecuteFromWrongStatus() {
        Ctx c = mixedDeliveredOrder(60_000L, 0L, 100_000L);
        var lines = linesOf(c.order());
        ReturnRequest r = returnRequests.submit(c.userId(), c.order().getPublicToken(),
                ReturnType.NON_QUALITY_ISSUE, Map.of(lines.get(0).getId(), 1), "买错了", evidence(c.userId()));

        assertThatThrownBy(() -> refunds.execute(r.getPublicToken()))
                .isInstanceOf(AppException.class).hasMessageContaining("不可执行退款");
    }

    // ---------- 🔒 FR-100A 规则 1：能力缺席 ----------

    @Test
    @DisplayName("🔒🔒 服务层不存在任何「PawCoin 段退成真钱」的可达路径（能力缺席，非权限判断）")
    void noReachablePathTurnsCoinSegmentIntoCash() {
        // ① 方法名层面：没有任何暗示提现 / 打款 / 折现的方法
        for (var m : RefundExecutionService.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertThat(n).doesNotContain("payout").doesNotContain("withdraw")
                    .doesNotContain("tocash").doesNotContain("cashout");
        }
        // ② 源码层面：本类对钱包的唯一写法是 credit（进钱包），没有 debit
        String src = readSource(
                "src/main/java/com/tailtopia/shop/returns/service/RefundExecutionService.java");
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertThat(code).contains("wallet.credit(");
        assertThat(code).as("🔴 出现 debit 意味着本类能把币从钱包里拿走 —— 那是套现的第一步")
                .doesNotContain("wallet.debit(");
        // ③ 数据模型层面：cash_destination 只描述现金段，PawCoin 段没有「去哪」这个字段
        assertThat(com.tailtopia.shop.returns.domain.CashDestination.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("TO_BANK", "TO_PAWCOIN");
    }

    @Test
    @DisplayName("🔒 PawCoin 段的退回一律走 credit(REFUND)，现金段退银行时【一分币都不进钱包】")
    void bankPayoutDoesNotCreditCoinForCashSegment() {
        Ctx c = mixedDeliveredOrder(60_000L, 0L, 100_000L);
        var lines = linesOf(c.order());
        long before = wallet.balanceOf(c.userId());
        ReturnRequest r = readyForRefund(c, ReturnType.NON_QUALITY_ISSUE,
                Map.of(lines.get(0).getId(), 1), CashDestination.TO_BANK);

        var out = refunds.execute(r.getPublicToken());

        // 🔴 钱包只涨了 PawCoin 段那部分；现金段没有被"顺手"也变成币
        assertThat(wallet.balanceOf(c.userId())).isEqualTo(before + out.coinRefunded());
        assertThat(out.cashRefunded()).isPositive();
    }

    private static String readSource(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到源码：" + path, e);
        }
    }
}
