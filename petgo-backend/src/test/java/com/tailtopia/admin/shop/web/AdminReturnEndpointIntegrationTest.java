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
import com.tailtopia.admin.shop.service.AdminReturnService;
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
    /** V1.3.0 Story 10.1：页签计数与页签归属直接问服务层（不受左栏第一页只放 20 条影响）。 */
    @Autowired
    private AdminReturnService adminReturnService;
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

    private ReturnRequest submitReturn(Ctx c, ReturnType type) {
        long lineId = orderLines.findByOrderIdOrderByIdAsc(c.order().getId()).get(0).getId();
        return returnRequests.submit(c.userId(), c.order().getPublicToken(), type,
                Map.of(lineId, 1), "note",
                type.isUndelivered() ? null : evidence(c.userId()));
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

    // ---------- V1.3.0 Story 10.1：A7 模板 A 工作台（AC1 / AC2 / AC5 / AC6） ----------

    @Test
    @DisplayName("工作台整页 200：五页签 + 左栏队列都在一页里（不再有独立详情页）")
    void workbenchPageRendersTabsAndQueue() throws Exception {
        Ctx c = deliveredOrder();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        String html = mvc.perform(get("/admin/shop/returns")
                        .with(authentication(staffWith(AdminPermissions.REFUND_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-workbench").contains("wb-detail-body");
        // 五个页签的紫计数 id 必须都在 —— 处置 fragment 靠同 id oob 替换它们
        for (String tab : java.util.List.of("pending", "shipback", "inspect", "refund", "closed")) {
            assertThat(html).as("缺页签计数 " + tab).contains("shop-return-tab-count-" + tab);
        }
        // ⚠️ **不断言「这一条出现在 HTML 里」**：队列是先进先出的第一页（20 条），而
        //    ApiIntegrationTest 不回滚，共享库里积压的待审核申请会把新造的这条挤到后面几页 ——
        //    那种断言单跑绿、全量跑随机红，是本仓库最误导人的一类失败。落在哪个页签问服务层。
        assertThat(adminReturnService.page(AdminReturnService.Tab.PENDING, null, null, 0, 1000)
                .getContent().stream().map(ReturnRequest::getPublicToken))
                .as("刚提交的申请应落在「待审核」页签").contains(r.getPublicToken());
    }

    @Test
    @DisplayName("HX-Request 下队列返的是行片段，不是整页（否则整页会被塞进左栏）")
    void queueUnderHtmxReturnsRowsFragment() throws Exception {
        Ctx c = deliveredOrder();
        submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        String body = mvc.perform(get("/admin/shop/returns").header("HX-Request", "true")
                        .with(authentication(staffWith(AdminPermissions.REFUND_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("<html").doesNotContain("data-workbench");
        assertThat(body).contains("q-row");
    }

    /**
     * 🔴 AC5：整页详情退役，<b>旧地址 404、不做跳转</b>（D-23）。
     *
     * <p>这条路径上的 mapping 按设计保留（T1「零新端点」：htmx 请求返右栏片段），
     * 所以 {@code AdminRetiredRoutesTest} 那种「不该有 GET 映射」的判据在这里不适用 ——
     * 「直达返 404」只能在这里钉。302 同样不行：留个跳转壳，旧地址就永远删不掉。
     */
    @Test
    @DisplayName("🔴 旧详情地址直达 → 404（不是 302，不是 200）；带 HX-Request 才返右栏五区片段")
    void oldDetailPageIsRetiredButTheSamePathServesTheRightPaneFragment() throws Exception {
        Ctx c = deliveredOrder();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);
        Authentication staff = staffWith(AdminPermissions.REFUND_VIEW);

        mvc.perform(get("/admin/shop/returns/{t}", r.getPublicToken()).with(authentication(staff)))
                .andExpect(status().isNotFound());

        String panel = mvc.perform(get("/admin/shop/returns/{t}", r.getPublicToken())
                        .header("HX-Request", "true").with(authentication(staff)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(panel).doesNotContain("<html");
        // 五区里最不能丢的两样：五步进度条、8 行退款试算卡的合计行（AC1 ③ 加粗「总退回（含补偿）」）
        assertThat(panel).contains("rf-flow--5");
        assertThat(panel).contains("sr-grand");
    }

    @Test
    @DisplayName("htmx 处置成功 → 右栏 done 片段：data-next-id + oob 删行 + 五页签计数（AC2）")
    void htmxApproveReturnsDoneFragmentWithNextIdAndOobCounts() throws Exception {
        Ctx c = deliveredOrder();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        String body = mvc.perform(post("/admin/shop/returns/{t}/approve", r.getPublicToken())
                        .header("HX-Request", "true")
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // data-next-id 为空时 Thymeleaf 整个去掉该属性 —— 所以 data-done 才是「这是处置结果」的可靠标记
        assertThat(body).contains("data-done");
        assertThat(body).contains("hx-swap-oob=\"delete\"").contains("shop-return-row-" + r.getPublicToken());
        assertThat(body).contains("shop-return-tab-count-pending");
        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.AWAIT_SHIPBACK);
    }

    @Test
    @DisplayName("🔒 htmx 下越权处置 → 403 + forbidden 片段（点名所缺权限），不是整页 denied")
    void htmxForbiddenReturnsTheInlineForbiddenFragment() throws Exception {
        Ctx c = deliveredOrder();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        var res = mvc.perform(post("/admin/shop/returns/{t}/approve", r.getPublicToken())
                        .header("HX-Request", "true")
                        .with(authentication(staffWith(AdminPermissions.REFUND_VIEW)))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andReturn().getResponse();

        assertThat(res.getHeader("HX-Reswap")).isEqualTo("innerHTML");
        assertThat(res.getContentAsString()).doesNotContain("<html");
        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("htmx 下业务失败 → 422 行内错误片段（驳回没填理由），单据状态不动")
    void htmxValidationFailureReturnsInlineError() throws Exception {
        Ctx c = deliveredOrder();
        ReturnRequest r = submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        var res = mvc.perform(post("/admin/shop/returns/{t}/reject", r.getPublicToken())
                        .header("HX-Request", "true")
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE)))
                        .with(csrf()).param("reason", " "))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse();

        assertThat(res.getHeader("HX-Reswap")).isEqualTo("innerHTML");
        assertThat(returns.findByPublicToken(r.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.PENDING_REVIEW);
    }

    /**
     * 页签计数与队列**同源**（AC4）：筛掉了的那些不能还算在计数里。
     *
     * <p>两处各算各的，界面上就会出现「待审核 3」配一张空队列 —— 运营只会当成加载失败。
     */
    @Test
    void tabCountsFollowTheSameFilterAsTheQueue() throws Exception {
        Ctx c = deliveredOrder();
        submitReturn(c, ReturnType.NON_QUALITY_ISSUE);

        java.util.Map<String, Long> unfiltered = adminReturnService.counts(null, null);
        java.util.Map<String, Long> filtered =
                adminReturnService.counts(ReturnType.QUALITY_ISSUE, null);

        assertThat(unfiltered.get("pending")).isPositive();
        assertThat(filtered.get("pending"))
                .as("筛了「质量问题」，刚造的这条是「非质量问题」，不该还被数进去")
                .isLessThan(unfiltered.get("pending"));
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

    // ==================== V1.3.0 Story 10.4：B19 模板 B + 抽屉（AC4）====================

    @Test
    @DisplayName("B19 整页：常驻业务定位提示 + 抽屉壳；HX-Request 返行片段")
    void precedentsPageRendersHintAndDrawerShell() throws Exception {
        Authentication staff = staffWith(AdminPermissions.REFUND_APPROVE);

        String html = mvc.perform(get("/admin/shop/return-precedents").with(authentication(staff)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 🔴 这句是 AC 的一部分不是装饰（SPEC-24）：删掉它，客服会把「查过判例」当成「查过风险」
        assertThat(html).as("页头的业务定位提示必须常驻").contains("一致性工具");
        assertThat(html).contains("shop-precedent-drawer-body").contains("shop-precedent-rows");
        assertThat(html).as("沉淀表单已收进抽屉，整页上不该再有页尾那张常驻表单卡")
                .doesNotContain("name=\"evidenceKeys\"");

        String rows = mvc.perform(get("/admin/shop/return-precedents").header("HX-Request", "true")
                        .with(authentication(staff)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(rows).doesNotContain("<html").doesNotContain("shop-precedent-drawer-body");
    }

    /** AC4：抽屉表单走 {@code ?create=1} 复用同一条 mapping —— 零新端点。 */
    @Test
    @DisplayName("B19 抽屉表单走 ?create=1（零新端点）")
    void precedentDrawerComesFromTheListMapping() throws Exception {
        String form = mvc.perform(get("/admin/shop/return-precedents").param("create", "1")
                        .header("HX-Request", "true")
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(form).doesNotContain("<html").contains("f-situation").contains("f-judged");
        assertThat(form).as("提交打到既有端点").contains("/admin/shop/return-precedents");
    }

    /**
     * AC4：抽屉里沉淀成功 → oob 整表 + toast + 关抽屉。
     *
     * <p>🔴 重拉的是<b>不带检索词</b>的全量：刚沉淀的那条多半不匹配运营此刻的检索词，
     * 按 q 重拉会得到「保存成功了但列表里找不到它」—— 最像失败的一种成功。
     */
    @Test
    @DisplayName("B19 抽屉沉淀成功：oob 整表（含新沉淀那条）+ toast + HX-Trigger 关抽屉")
    void precedentAddedFromDrawerRefreshesTheWholeTable() throws Exception {
        var res = mvc.perform(post("/admin/shop/return-precedents")
                        .header("HX-Request", "true").header("HX-Target", "shop-precedent-drawer-body")
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE))).with(csrf())
                        .param("situation", "抽屉沉淀的判例 A")
                        .param("judgedOpened", "false")
                        .param("rationale", "内袋密封完好"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();

        assertThat(body).doesNotContain("<html");
        assertThat(body).contains("hx-swap-oob").contains("id=\"shop-precedent-rows\"");
        assertThat(body).as("新沉淀的那条要出现在重拉回来的整表里").contains("抽屉沉淀的判例 A");
        assertThat(res.getResponse().getHeader("HX-Trigger"))
                .as("不关抽屉的话，保存成功后表单还盖在表格上，运营会以为没生效")
                .contains("admin:drawer-close");
    }

    @Test
    @DisplayName("B19 抽屉提交理由为空 → 4xx 且 HX-Retarget 落抽屉体（不是整表被红字换掉）")
    void precedentValidationErrorIsRetargetedToTheDrawer() throws Exception {
        var res = mvc.perform(post("/admin/shop/return-precedents")
                        .header("HX-Request", "true").header("HX-Target", "shop-precedent-drawer-body")
                        .with(authentication(staffWith(AdminPermissions.REFUND_APPROVE))).with(csrf())
                        .param("situation", "x").param("judgedOpened", "true").param("rationale", " "))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isBetween(400, 499);
        assertThat(res.getResponse().getHeader("HX-Retarget")).isEqualTo("#shop-precedent-drawer-body");
        assertThat(res.getResponse().getContentAsString()).doesNotContain("hx-swap-oob");
    }

    /** 🔒 AC4：只读账号（{@code refund.view}）看得到判例，但沉淀入口不渲染、提交也被服务端拒。 */
    @Test
    @DisplayName("🔒 B19 只读账号：能看、没有沉淀入口、htmx 提交 → 403")
    void precedentWritesRequireRefundApprove() throws Exception {
        Authentication viewer = staffWith(AdminPermissions.REFUND_VIEW);

        String html = mvc.perform(get("/admin/shop/return-precedents").with(authentication(viewer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("🔒 没有这道门，只读账号能打开抽屉、填完提交才收到 403 —— 那是一次白填")
                .doesNotContain("data-drawer-res=\"shop-precedent\"");

        mvc.perform(post("/admin/shop/return-precedents")
                        .header("HX-Request", "true").header("HX-Target", "shop-precedent-drawer-body")
                        .with(authentication(viewer)).with(csrf())
                        .param("situation", "x").param("judgedOpened", "true").param("rationale", "y"))
                .andExpect(status().isForbidden());
    }
}
