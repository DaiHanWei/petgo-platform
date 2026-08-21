package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
import com.tailtopia.shop.returns.domain.ReturnType;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * L1：后台退货审核 / 质检入库 / 判例库（Story 5.3 AB-12A · 5.4 AB-12B · 5.6 AB-12D）。
 *
 * <p>🔴 本类的三条核心断言：
 * <ol>
 *   <li><b>不新建审核通道</b> —— 权限沿用既有退款审批三级：只有 {@code refund.view} 的账号
 *       批不了、只有 {@code refund.approve} 的账号打不了款；</li>
 *   <li><b>只有质检通过的退货才进可售库存</b>，且以退货入库批次入库（S-9）；</li>
 *   <li><b>质检不通过必须选处置方式，不留悬空</b>（S-10）。</li>
 * </ol>
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class AdminReturnEndpointIntegrationTest extends ApiIntegrationTest {

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

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "ar" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "ar" + n);
    }

    /** 🔴 库存走真实采购入库：退货入库要按 S-9 取最近一次采购价，没有采购历史就登记不了。 */
    private String seedSku(long stock, long price) {
        String pToken = "arp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "ars" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 0, 0)", sid);
        movements.receivePurchase(sid, stock, "PO-" + SEQ.incrementAndGet(), "供应商", price / 2,
                LocalDate.now(), ACTOR);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class,
                token);
    }

    private Ctx deliveredOrder() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "ar-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Kar" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.markDeliveredByAdmin(o.getPublicToken());
        return new Ctx(uid, sku, orders.findByPublicToken(o.getPublicToken()).orElseThrow());
    }

    private record Ctx(long userId, String skuToken, ShopOrder order) {
    }

    private ReturnRequest submitReturn(Ctx c, ReturnType type) {
        long lineId = orderLines.findByOrderIdOrderByIdAsc(c.order().getId()).get(0).getId();
        return returnRequests.submit(c.userId(), c.order().getPublicToken(), type,
                Map.of(lineId, 1), "note",
                type == ReturnType.QUALITY_ISSUE ? java.util.List.of("ev1") : null);
    }

    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "ret-" + n + "@tailtopia.test", "退货测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    // ---------- 🔴 不新建审核通道：沿用退款审批三级职责分离 ----------

    @Test
    @DisplayName("🔒 无退款权限的账号访问退货队列 → 403")
    void noRefundPermissionForbidden() throws Exception {
        mvc.perform(get("/admin/shop/returns")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("🔒 只有 refund.view 的账号批不了退货 → 403（批准是 refund.approve）")
    void viewOnlyCannotApprove() throws Exception {
        Ctx c = deliveredOrder();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        mvc.perform(post("/admin/shop/returns/{t}/approve", r.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.REFUND_VIEW)))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("🔒 只有 refund.approve 的账号打不了款 → 403（打款是 refund.payout，职责分离）")
    void approverCannotPayout() throws Exception {
        // 拒收只能从【已发货】态提起 —— 已签收的订单该走普通退货
        Ctx c = shippedOrder();
        ReturnRequest r = submitReturn(c, ReturnType.REFUSED_ON_DELIVERY);

        mvc.perform(post("/admin/shop/returns/{t}/refund", r.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE)))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---------- 5.3 审核 ----------

    @Test
    @DisplayName("批准非质量问题 → 待寄回；审计摘要记下两处运费归属（它们决定退款金额）")
    void approveMovesToAwaitShipbackAndAudits() throws Exception {
        Ctx c = deliveredOrder();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        mvc.perform(post("/admin/shop/returns/{t}/approve", r.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE)))
                        .with(csrf()))
                .andExpect(flash().attributeExists("notice"));

        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.AWAIT_SHIPBACK);
        String summary = jdbc.queryForObject("""
                SELECT summary FROM admin_audit_logs
                WHERE action_type = ? AND target_id = ?""",
                String.class, AuditActions.SHOP_RETURN_REVIEWED, r.getPublicToken());
        assertThat(summary).contains("回程运费").contains("去程运费退回");
    }

    @Test
    @DisplayName("🔴 拒收 / 发货前取消跳过寄回与质检，批准后直接进入退款执行")
    void refusedOnDeliverySkipsShipbackAndInspection() throws Exception {
        Ctx c = deliveredOrder();
        // 造一笔仍在已发货态的订单来走拒收
        Ctx shipped = shippedOrder();
        ReturnRequest r = submitReturn(shipped, ReturnType.REFUSED_ON_DELIVERY);

        mvc.perform(post("/admin/shop/returns/{t}/approve", r.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE)))
                        .with(csrf()));

        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.REFUNDING);
        assertThat(c.order()).isNotNull();
    }

    @Test
    @DisplayName("🔴 驳回必须填理由，且订单回到申请前状态（SPEC-6 ②）")
    void rejectRequiresReasonAndRestoresOrder() throws Exception {
        Ctx c = shippedOrder();
        ReturnRequest r = submitReturn(c, ReturnType.REFUSED_ON_DELIVERY);
        Authentication staff = staffWith(AdminPermissions.REFUND_APPROVE);

        mvc.perform(post("/admin/shop/returns/{t}/reject", r.getPublicToken())
                        .with(authentication(staff)).with(csrf()).param("reason", ""))
                .andExpect(flash().attributeExists("error"));

        mvc.perform(post("/admin/shop/returns/{t}/reject", r.getPublicToken())
                        .with(authentication(staff)).with(csrf()).param("reason", "无正当理由"))
                .andExpect(flash().attributeExists("notice"));
        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.REJECTED);
        assertThat(orders.findByPublicToken(c.order().getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(com.tailtopia.shop.order.domain.ShopOrderStatus.SHIPPED);
    }

    // ---------- 5.4 质检与入库 ----------

    @Test
    @DisplayName("🔴 质检通过 → 可售库存增加，且以【退货入库批次】入库、采购单号 = 原订单号（S-9）")
    void inspectionPassRestocksAsReturnBatch() throws Exception {
        Ctx c = deliveredOrder();
        long sid = skuId(c.skuToken());
        long before = inventory.findBySkuId(sid).orElseThrow().getActual();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);
        Authentication staff = staffWith(AdminPermissions.REFUND_APPROVE);

        mvc.perform(post("/admin/shop/returns/{t}/approve", r.getPublicToken())
                .with(authentication(staff)).with(csrf()));
        mvc.perform(post("/admin/shop/returns/{t}/shipback", r.getPublicToken())
                .with(authentication(staff)).with(csrf())
                .param("carrier", "JNE").param("trackingNo", "SB" + SEQ.incrementAndGet())
                .param("fee", "12000"));
        mvc.perform(post("/admin/shop/returns/{t}/inspect-pass", r.getPublicToken())
                        .with(authentication(staff)).with(csrf()).param("note", "完好"))
                .andExpect(flash().attributeExists("notice"));

        assertThat(inventory.findBySkuId(sid).orElseThrow().getActual()).isEqualTo(before + 1);
        Long batch = jdbc.queryForObject("""
                SELECT count(*) FROM inventory_movements
                WHERE sku_id = ? AND movement_type = 'RETURN_INBOUND' AND purchase_no = ?""",
                Long.class, sid, c.order().getPublicToken());
        assertThat(batch).isEqualTo(1L);
        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.REFUNDING);
    }

    @Test
    @DisplayName("🔴 质检不通过 → 不进可售库存；必须记处置方式（S-10 不留悬空）")
    void inspectionFailDoesNotRestockAndNeedsDisposal() throws Exception {
        Ctx c = deliveredOrder();
        long sid = skuId(c.skuToken());
        long before = inventory.findBySkuId(sid).orElseThrow().getActual();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);
        Authentication staff = staffWith(AdminPermissions.REFUND_APPROVE);

        mvc.perform(post("/admin/shop/returns/{t}/approve", r.getPublicToken())
                .with(authentication(staff)).with(csrf()));
        mvc.perform(post("/admin/shop/returns/{t}/shipback", r.getPublicToken())
                .with(authentication(staff)).with(csrf())
                .param("carrier", "JNE").param("trackingNo", "SB" + SEQ.incrementAndGet()));

        // 不选处置方式 → 拒绝
        mvc.perform(post("/admin/shop/returns/{t}/inspect-fail", r.getPublicToken())
                        .with(authentication(staff)).with(csrf())
                        .param("note", "已开封").param("disposal", ""))
                .andExpect(flash().attributeExists("error"));
        // 选「退回用户」但不给回寄单号 → 拒绝
        mvc.perform(post("/admin/shop/returns/{t}/inspect-fail", r.getPublicToken())
                        .with(authentication(staff)).with(csrf())
                        .param("note", "已开封").param("disposal", "RETURN_TO_USER"))
                .andExpect(flash().attributeExists("error"));
        // 补齐回寄单号 → 通过
        mvc.perform(post("/admin/shop/returns/{t}/inspect-fail", r.getPublicToken())
                        .with(authentication(staff)).with(csrf())
                        .param("note", "已开封").param("disposal", "RETURN_TO_USER")
                        .param("shipBackTrackingNo", "RB123"))
                .andExpect(flash().attributeExists("notice"));

        ReturnRequest after = returns.findByPublicToken(r.getPublicToken()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(after.getRejectDisposal()).isNotNull();
        assertThat(after.getReturnShipBackTrackingNo()).isEqualTo("RB123");
        assertThat(inventory.findBySkuId(sid).orElseThrow().getActual())
                .as("🔴 只有质检通过的退货才进可售库存").isEqualTo(before);
    }

    // ---------- 5.6 判例库 ----------

    @Test
    @DisplayName("判例可沉淀且可被同类情形检索到；理由必填")
    void precedentCanBeAddedAndSearched() throws Exception {
        Authentication staff = staffWith(AdminPermissions.REFUND_APPROVE);

        mvc.perform(post("/admin/shop/return-precedents")
                        .with(authentication(staff)).with(csrf())
                        .param("situation", "外包装已拆但内袋密封完好")
                        .param("judgedOpened", "false")
                        .param("rationale", "内袋未破，不影响二次销售"))
                .andExpect(flash().attributeExists("notice"));

        String html = mvc.perform(get("/admin/shop/return-precedents")
                        .with(authentication(staff)).param("q", "内袋"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("外包装已拆但内袋密封完好");

        // 理由必填 —— 没有理由的判例只会变成「因为上次这么判」的循环引用
        mvc.perform(post("/admin/shop/return-precedents")
                        .with(authentication(staff)).with(csrf())
                        .param("situation", "x").param("judgedOpened", "true")
                        .param("rationale", " "))
                .andExpect(flash().attributeExists("error"));
    }

    // ---------- 辅助 ----------

    private Ctx shippedOrder() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "ar-topup2:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Kar" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        return new Ctx(uid, sku, orders.findByPublicToken(o.getPublicToken()).orElseThrow());
    }
}
