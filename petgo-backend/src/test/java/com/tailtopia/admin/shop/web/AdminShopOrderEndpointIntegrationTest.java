package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.notify.domain.NotificationType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;

/**
 * L1 集成：后台电商订单发货与查询（Story 4.2 AB-11B · Story 4.3 AB-11A）。
 *
 * <p>🔒 本类的三条硬断言：
 * <ol>
 *   <li><b>列表不含 PII</b> —— 收件人姓名 / 电话 / 详细地址一个字都不能出现在列表 HTML 里；</li>
 *   <li><b>审计摘要不含号码</b> —— 只记命中条数与查询指纹（审计表永久保留、无 TTL）；</li>
 *   <li><b>按电话搜索是独立权限</b> —— 只有 {@code shop.order_view} 的账号用它必须 403。</li>
 * </ol>
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class AdminShopOrderEndpointIntegrationTest extends ApiIntegrationTest {

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
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;
    private static final String RECEIVER = "Budi Santoso";
    private static final String PHONE_RAW = "08123456789";
    private static final String ADDRESS_LINE = "Jl. Melawai IV No. 12";

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "aso" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class,
                "aso" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "ap" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "as" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                sid, stock);
        return sToken;
    }

    /** 已付款待发货订单，收件信息用固定的 PII 三件套（供泄漏断言比对）。 */
    private ShopOrder paidOrder(long uid) {
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "aso-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Kaso" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields(RECEIVER, PHONE_RAW, "DKI Jakarta",
                "Jakarta Selatan", kec, ADDRESS_LINE, "12160", "Rumah")).getPublicToken();
        carts.add(uid, seedSku(10, 100_000L), 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        return orders.findByPublicToken(o.getPublicToken()).orElseThrow();
    }

    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "ord-" + n + "@tailtopia.test", "订单测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    // ---------- 权限 ----------

    @Test
    @DisplayName("🔒 无模块 11 权限的 STAFF 访问订单列表 → 403")
    void withoutOrderPermissionForbidden() throws Exception {
        mvc.perform(get("/admin/shop/orders")
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("🔒 只有 order_view 的账号不能发货 → 403（发货是 order_fulfill）")
    void viewOnlyCannotShip() throws Exception {
        ShopOrder order = paidOrder(seedUser());
        mvc.perform(post("/admin/shop/orders/{t}/ship", order.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW)))
                        .with(csrf())
                        .param("carrier", "JNE").param("trackingNo", "JP1")
                        .param("carrierCost", "1000"))
                .andExpect(status().isForbidden());
        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("🔒 只有 order_view 的账号按电话搜索 → 403（独立权限位，NFR-11）")
    void viewOnlyCannotSearchByPhone() throws Exception {
        mvc.perform(get("/admin/shop/orders")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW)))
                        .param("phone", "8123456789"))
                .andExpect(status().isForbidden());
    }

    // ---------- 发货（AB-11B） ----------

    @Test
    @DisplayName("发货：录承运商 + 单号 + 承运成本 → 订单转 SHIPPED，写审计，发通知")
    void shipWritesAuditAndNotifies() throws Exception {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        String tracking = "JP" + SEQ.incrementAndGet();

        mvc.perform(post("/admin/shop/orders/{t}/ship", order.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_FULFILL)))
                        .with(csrf())
                        .param("carrier", "JNE").param("trackingNo", tracking)
                        .param("carrierCost", "18000"))
                .andExpect(redirectedUrl("/admin/shop/orders/" + order.getPublicToken()))
                .andExpect(flash().attributeExists("notice"));

        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.SHIPPED);

        // 审计：记承运商与单号（非 PII 可记）
        String summary = jdbc.queryForObject("""
                SELECT summary FROM admin_audit_logs
                WHERE action_type = ? AND target_id = ?""",
                String.class, AuditActions.SHOP_ORDER_SHIPPED, order.getPublicToken());
        assertThat(summary).contains("JNE").contains(tracking);
        // 🔒 但收件人一个字都不能进审计（审计表永久保留、无 TTL）
        assertThat(summary).doesNotContain(RECEIVER).doesNotContain("8123456789")
                .doesNotContain(ADDRESS_LINE);

        // 通知：类型 + 深链目标为不可枚举订单 token
        Long notified = jdbc.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE recipient_user_id = ? AND type = ? AND target_ref = ?""",
                Long.class, uid, NotificationType.SHOP_ORDER_SHIPPED.name(),
                order.getPublicToken());
        assertThat(notified).isEqualTo(1L);
    }

    @Test
    @DisplayName("🔴 承运成本不填 → 校验失败（S-11：不录则毛利看板缺行）")
    void carrierCostIsRequired() throws Exception {
        ShopOrder order = paidOrder(seedUser());
        mvc.perform(post("/admin/shop/orders/{t}/ship", order.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_FULFILL)))
                        .with(csrf())
                        .param("carrier", "JNE").param("trackingNo", "JP-nocost"))
                .andExpect(flash().attributeExists("error"));
        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("未知承运商（如已被 C-14 砍掉的 GoSend）被拒，不默认到某一家")
    void unknownCarrierRejected() throws Exception {
        ShopOrder order = paidOrder(seedUser());
        mvc.perform(post("/admin/shop/orders/{t}/ship", order.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_FULFILL)))
                        .with(csrf())
                        .param("carrier", "GoSend").param("trackingNo", "GS1")
                        .param("carrierCost", "1"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @DisplayName("SPEC-2 出口①：后台「标记已送达」→ DELIVERED，并留操作人与时间（审计）")
    void markDeliveredIsAudited() throws Exception {
        ShopOrder order = paidOrder(seedUser());
        Authentication staff = staffWith(AdminPermissions.SHOP_ORDER_FULFILL);
        mvc.perform(post("/admin/shop/orders/{t}/ship", order.getPublicToken())
                        .with(authentication(staff)).with(csrf())
                        .param("carrier", "SICEPAT")
                        .param("trackingNo", "SC" + SEQ.incrementAndGet())
                        .param("carrierCost", "0"));

        mvc.perform(post("/admin/shop/orders/{t}/mark-delivered", order.getPublicToken())
                        .with(authentication(staff)).with(csrf()))
                .andExpect(flash().attributeExists("notice"));

        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.DELIVERED);
        Long audited = jdbc.queryForObject("""
                SELECT count(*) FROM admin_audit_logs
                WHERE action_type = ? AND target_id = ? AND actor_account_id IS NOT NULL""",
                Long.class, AuditActions.SHOP_ORDER_MARKED_DELIVERED, order.getPublicToken());
        assertThat(audited).isEqualTo(1L);
    }

    // ---------- 🔒 列表与 PII（AB-11A） ----------

    @Test
    @DisplayName("🔒 订单列表 HTML 里不得出现收件人姓名 / 电话 / 详细地址")
    void listPageLeaksNoPii() throws Exception {
        paidOrder(seedUser());
        String html = mvc.perform(get("/admin/shop/orders")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain(RECEIVER);
        assertThat(html).doesNotContain("8123456789").doesNotContain(PHONE_RAW);
        assertThat(html).doesNotContain(ADDRESS_LINE);
    }

    @Test
    @DisplayName("🔒 无 order_phone_search 权限时，列表页不渲染电话搜索框")
    void phoneSearchBoxHiddenWithoutPermission() throws Exception {
        mvc.perform(get("/admin/shop/orders")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW))))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                                "name=\"phone\""))));
    }

    @Test
    @DisplayName("详情页展示收件信息与两段支付构成（这里才是 PII 的唯一出口）")
    void detailPageShowsShipToAndPaymentSplit() throws Exception {
        ShopOrder order = paidOrder(seedUser());
        String html = mvc.perform(get("/admin/shop/orders/{t}", order.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(RECEIVER).contains(ADDRESS_LINE);
        assertThat(html).contains("PAWCOIN");     // 两段支付构成
        assertThat(html).contains(order.getPublicToken());
    }

    // ---------- 🔒 按电话搜索（AB-11A） ----------

    @Test
    @DisplayName("🔴 C-15：08xx / 8xx / +62 8xx 三种写法都能搜到同一批订单")
    void phoneSearchMatchesAllThreeInputForms() throws Exception {
        ShopOrder order = paidOrder(seedUser());
        Authentication staff = staffWith(AdminPermissions.SHOP_ORDER_VIEW,
                AdminPermissions.SHOP_ORDER_PHONE_SEARCH);

        for (String form : List.of("08123456789", "8123456789", "+62 812-3456-789")) {
            String html = mvc.perform(get("/admin/shop/orders")
                            .with(authentication(staff)).param("phone", form))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as("输入形式 %s 没搜到", form).contains(order.getPublicToken());
        }
    }

    @Test
    @DisplayName("🔒 按电话搜索写审计，且摘要里【没有号码】，只有命中数与指纹")
    void phoneSearchAuditOmitsTheNumber() throws Exception {
        paidOrder(seedUser());
        mvc.perform(get("/admin/shop/orders")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW,
                                AdminPermissions.SHOP_ORDER_PHONE_SEARCH)))
                        .param("phone", "08123456789"))
                .andExpect(status().isOk());

        List<String> summaries = jdbc.queryForList("""
                SELECT summary FROM admin_audit_logs WHERE action_type = ?
                ORDER BY id DESC""", String.class, AuditActions.SHOP_ORDER_SEARCHED_BY_PHONE);
        assertThat(summaries).isNotEmpty();
        assertThat(summaries.get(0)).contains("命中").contains("指纹");
        assertThat(summaries).allSatisfy(s -> {
            assertThat(s).doesNotContain("8123456789");
            assertThat(s).doesNotContain("08123456789");
            assertThat(s).doesNotContain("628123456789");
        });
    }

    @Test
    @DisplayName("🔴 位数过少的电话查询被拒 —— 再短就不是搜索而是遍历")
    void tooShortPhoneQueryRejected() throws Exception {
        String html = mvc.perform(get("/admin/shop/orders")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW,
                                AdminPermissions.SHOP_ORDER_PHONE_SEARCH)))
                        .param("phone", "8123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("至少需要");
    }

    // ---------- 筛选（AB-11A） ----------

    @Test
    @DisplayName("按状态与订单号筛选")
    void filterByStatusAndToken() throws Exception {
        ShopOrder pendingShipment = paidOrder(seedUser());
        Authentication staff = staffWith(AdminPermissions.SHOP_ORDER_VIEW);

        String byToken = mvc.perform(get("/admin/shop/orders").with(authentication(staff))
                        .param("orderToken", pendingShipment.getPublicToken()))
                .andReturn().getResponse().getContentAsString();
        assertThat(byToken).contains(pendingShipment.getPublicToken());

        String byStatus = mvc.perform(get("/admin/shop/orders").with(authentication(staff))
                        .param("status", "COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(byStatus).doesNotContain(pendingShipment.getPublicToken());
    }

    @Test
    @DisplayName("时间范围筛选含当天（用次日零点做开区间上界）")
    void dateRangeIncludesToday() throws Exception {
        ShopOrder order = paidOrder(seedUser());
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String html = mvc.perform(get("/admin/shop/orders")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW)))
                        .param("from", today).param("to", today))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(order.getPublicToken());
    }
}
