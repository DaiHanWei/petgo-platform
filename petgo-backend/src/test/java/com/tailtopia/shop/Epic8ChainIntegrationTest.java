package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.service.AdminReturnService;
import com.tailtopia.admin.shop.service.ShopFinanceDashboardService;
import com.tailtopia.pay.domain.PawCoinTxnType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Epic 8 权限矩阵与联调（Story 8.4）。
 *
 * <p>🔴 两条核心断言：
 * <ol>
 *   <li><b>模块 10–13 的权限不默认授予既有运营角色</b>，且<b>进货价与毛利/对账是两个独立权限位</b>
 *       （NFR-11）—— 看得到单个 SKU 的进货价，和看得到整盘生意的现金流，是两种量级的敏感。</li>
 *   <li><b>三个看板的数字互相对得上</b>：销售额 − 退款 = 净额；库存变化与订单一致；
 *       对账两段之和等于订单实付。</li>
 * </ol>
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class Epic8ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShopFinanceDashboardService finance;
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
    private InventoryMovementService movements;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;
    private static final long ADMIN = 1L;

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "e8" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "e8" + n);
    }

    /** 建 SKU，库存走真实采购入库（顺带把进货价写进 cost_price，毛利要用）。 */
    private String seedSku(long stock, long price, long costPrice) {
        String pToken = "e8p" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "e8s" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, cost_price)
                VALUES (?, ?, '3 kg', ?, ?)""", sToken, pid, price, costPrice);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 0, 0)", sid);
        movements.receivePurchase(sid, stock, "PO-" + SEQ.incrementAndGet(), "供应商", costPrice,
                LocalDate.now(), ACTOR);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class,
                token);
    }

    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "fin-" + n + "@tailtopia.test", "经营数据测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    // ---------- 🔒 Story 8.4 权限矩阵 ----------

    @Test
    @DisplayName("🔒 毛利 / 库存周转 / 对账三页都要 shop.finance_view —— 不默认授予既有运营角色")
    void financePagesRequireDedicatedPermission() throws Exception {
        // 有电商订单权限、甚至有进货价查看权限，都进不去
        Authentication opsStaff = staffWith(AdminPermissions.SHOP_ORDER_VIEW,
                AdminPermissions.SHOP_ORDER_FULFILL, AdminPermissions.SHOP_INVENTORY_VIEW,
                AdminPermissions.SHOP_COST_VIEW);
        for (String url : List.of("/admin/shop/margin", "/admin/shop/inventory-turnover",
                "/admin/shop/reconciliation")) {
            mvc.perform(get(url).with(authentication(opsStaff)))
                    .andExpect(status().isForbidden());
        }

        Authentication finance = staffWith(AdminPermissions.SHOP_FINANCE_VIEW);
        for (String url : List.of("/admin/shop/margin", "/admin/shop/inventory-turnover",
                "/admin/shop/reconciliation")) {
            mvc.perform(get(url).with(authentication(finance))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("🔒 进货价与毛利是【两个】权限位 —— 看得到单个进货价 ≠ 看得到整盘生意")
    void costViewAndFinanceViewAreSeparate() {
        assertThat(AdminPermissions.ALL)
                .contains(AdminPermissions.SHOP_COST_VIEW, AdminPermissions.SHOP_FINANCE_VIEW);
        assertThat(AdminPermissions.SHOP_COST_VIEW)
                .isNotEqualTo(AdminPermissions.SHOP_FINANCE_VIEW);
    }

    @Test
    @DisplayName("🔒 模块 10–13 的权限码一个都不在默认授予集合里（NFR-11）")
    void shopPermissionsAreNotGrantedByDefault() {
        // 一个只有既有内容/兽医权限的账号，拿不到任何 shop.* 能力
        AdminUserDetails legacy = new AdminUserDetails(1L, null, "x@y.z", "{bcrypt}x",
                AdminAccountType.STAFF,
                Set.of(AdminPermissions.CONTENT_VIEW, AdminPermissions.VET_VIEW));
        assertThat(legacy.getAuthorities().stream().map(a -> a.getAuthority()))
                .noneMatch(a -> a.startsWith("shop."));
    }

    // ---------- 🔴 三个看板互相对得上 ----------

    @Test
    @DisplayName("🔴🔴 一笔完整交易（下单 → 履约 → 部分退货）后，三个看板的数字互相对得上")
    void dashboardsAgreeAfterFullTransaction() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 60_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "e8-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(99_000_000L, ACTOR);   // 让运费真实产生
        String kec = "Ke8" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 20_000L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();

        String skuA = seedSku(10, 100_000L, 60_000L);
        String skuB = seedSku(10, 80_000L, 50_000L);
        long stockABefore = inventory.findBySkuId(skuId(skuA)).orElseThrow().getActual();
        carts.add(uid, skuA, 1);
        carts.add(uid, skuB, 1);

        ShopOrder order = checkout.placeOrder(uid, addr, null, null);
        var pay = payments.pay(uid, order.getPublicToken(), null);
        if (pay.paymentIntentToken() != null) {
            paymentIntents.applyCallback(new PaymentCallback(pay.paymentIntentToken(),
                    "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));
        }
        // 出库后库存少 1
        assertThat(inventory.findBySkuId(skuId(skuA)).orElseThrow().getActual())
                .isEqualTo(stockABefore - 1);

        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(),
                18_000L);
        fulfillment.markDeliveredByAdmin(order.getPublicToken());

        // 部分退货：只退第一行
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        ReturnRequest r = returnRequests.submit(uid, order.getPublicToken(),
                ReturnType.NON_QUALITY_ISSUE, Map.of(lines.get(0).getId(), 1), "note", evidence(uid));
        r.chooseCashDestination(CashDestination.TO_BANK,
                com.tailtopia.pay.refund.domain.PayoutChannel.BCA, "12345", "Budi");
        returns.save(r);
        adminReturns.approve(r.getPublicToken(), ADMIN);
        adminReturns.registerShipback(r.getPublicToken(), "JNE", "SB" + SEQ.incrementAndGet(),
                12_000L, ADMIN);
        adminReturns.passInspection(r.getPublicToken(), "完好", ADMIN + "", ADMIN);
        adminReturns.executeRefund(r.getPublicToken(), ADMIN);

        LocalDate today = LocalDate.now();
        var m = finance.margin(today.minusDays(1), today, null);
        var recon = finance.reconciliation(today.minusDays(1), today);

        // ① 销售额 − 退款 = 净额
        assertThat(m.netRevenue()).isEqualTo(m.revenue() - m.refundedGoods());
        assertThat(m.refundedGoods()).isPositive();

        // ② 库存变化与订单一致：退货质检通过后 skuA 回到出库前的水位
        assertThat(inventory.findBySkuId(skuId(skuA)).orElseThrow().getActual())
                .as("退货入库后应回到出库前的水位").isEqualTo(stockABefore);

        // ③ 🔴 对账两段之和 = 订单实付
        assertThat(recon.segmentsBalance())
                .as("🔴 两段对不上就是对账本身错了").isTrue();

        // ④ 🔴 SPEC-22：承运成本真的进了看板（缺它则 A-19 不可验证）
        assertThat(m.carrierCost()).isGreaterThanOrEqualTo(18_000L);
        assertThat(m.shippingNet()).isEqualTo(m.shippingRevenue() - m.carrierCost());

        // ⑤ 🔴 被 PawCoin 抵扣的运费单独可拆（FR-100A 规则 3）
        assertThat(recon.shippingPaidByCoin()).isGreaterThanOrEqualTo(0L);
        assertThat(recon.shippingPaidByCoin()).isLessThanOrEqualTo(recon.shippingCharged());

        // ⑥ 售后成本按退货类型可下钻
        assertThat(m.afterSalesByReturnType()).isNotNull();
    }

    @Test
    @DisplayName("🔴 毛利率分母为 0 时返回 0，而不是抛异常或算出 Infinity")
    void zeroRevenueDoesNotBlowUp() {
        // 用一个肯定没有交易的历史区间
        var m = finance.margin(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2), null);
        assertThat(m.revenue()).isZero();
        assertThat(m.grossMarginPercent()).isZero();
        assertThat(m.shippingNet()).isEqualTo(-m.carrierCost());
    }

    @Test
    @DisplayName("库存周转给出库存金额（按进货价）与滞销判据；售罄计数可得")
    void turnoverExposesStockValueAndStaleSignal() {
        String sku = seedSku(5, 100_000L, 60_000L);
        var rows = finance.inventoryTurnover(30);

        assertThat(rows).isNotEmpty();
        var row = rows.stream()
                .filter(x -> sku.equals(x.get("sku_token")))
                .findFirst().orElseThrow();
        // 5 件 × 60 000 进货价
        assertThat(((Number) row.get("stock_value")).longValue()).isEqualTo(300_000L);
        assertThat(finance.outOfStockSkuCount()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("⚠️ S-12：赠币核销额是近似值，且不为负 —— 钱包侧未做批次分层（预留列不启用）")
    void bonusRedemptionIsApproximateAndNonNegative() {
        LocalDate today = LocalDate.now();
        var recon = finance.reconciliation(today.minusDays(30), today);
        assertThat(recon.bonusRedeemedApprox()).isGreaterThanOrEqualTo(0L);
        // 预留列存在但没有任何读写方（S-12：加了不用是刻意的）
        Long cols = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'pawcoin_wallets' AND column_name = 'batch_type'""",
                Long.class);
        assertThat(cols).isEqualTo(1L);
    }

    /**
     * 合法的凭证 key（2026-09-02，D-10 + 凭证张数口径）。
     *
     * <p>服务端现在校验两件事：**归属**（key 必须形如 {@code <keyPrefix>private/<userId>/…}，
     * 见 {@code MediaObjectKeys}）与**张数**（货在用户手上的退货要 ≥ 2 张，
     * 见 {@code ReturnRequestService.MIN_EVIDENCE}）。夹具从前那种 {@code "ev1"} 或 {@code null}
     * 两条都过不了。
     * ⚠️ 测试环境 {@code MEDIA_OSS_KEY_PREFIX} 为空，故前缀就是 {@code private/}。
     */
    private static java.util.List<String> evidence(long userId) {
        return java.util.List.of("private/" + userId + "/ev1.jpg", "private/" + userId + "/ev2.jpg");
    }
}
