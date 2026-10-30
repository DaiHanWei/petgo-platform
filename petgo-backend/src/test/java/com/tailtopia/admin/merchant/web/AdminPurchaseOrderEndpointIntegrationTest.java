package com.tailtopia.admin.merchant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.merchant.po.domain.PoShippingMethod;
import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderLine;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import com.tailtopia.merchant.po.repository.PurchaseOrderLineRepository;
import com.tailtopia.merchant.po.repository.PurchaseOrderRepository;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：门店进货单 ops 页（列表 / 详情 / 录入包裹 / 标记送达）。照 {@code AdminShopOrderEndpointIntegrationTest}。
 *
 * <p>🔒 硬断言：列表不含收货人 PII；审计摘要不含 PII；只有 {@code shop.order_view} 的账号不能写。
 */
class AdminPurchaseOrderEndpointIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/admin/merchant/purchase-orders";
    private static final String RECEIVER = "Siti Rahayu";
    private static final String PHONE = "+6281299887766";
    private static final String ADDRESS_LINE = "Jl. Kemang Raya No. 8";

    @Autowired
    private PurchaseOrderRepository orders;
    @Autowired
    private PurchaseOrderLineRepository lines;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 造数 ----------

    /** 已付款待发货的进货单（直接经仓储落库：本类测的是 ops 页，不是建单接口）。 */
    private PurchaseOrder paidPo() {
        long n = SEQ.incrementAndGet();
        String token = ("it" + Long.toString(n, 36) + "xxxxxxxxxxxxxxxxxxxx").substring(0, 22);
        String orderNo = "PO-IT-" + Long.toString(n, 36).toUpperCase();
        Instant now = Instant.now();
        PurchaseOrder po = PurchaseOrder.place(token, orderNo, 42L, PoShippingMethod.FREE, 500_000L,
                new AddressSnapshot(RECEIVER, PHONE, "DKI Jakarta", "Jakarta Selatan", "Mampang",
                        ADDRESS_LINE, "12730"),
                "Toko IT " + n, "+6281200000000", now);
        po.markPaid(now);
        po = orders.save(po);
        lines.save(PurchaseOrderLine.of(po.getId(), 1, 9_000_000L + n, "sku" + n, "Royal Canin", "3 kg",
                null, 250_000L, 2));
        return po;
    }

    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "po-" + n + "@tailtopia.test", "进货单测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    private PurchaseOrderStatus statusOf(PurchaseOrder po) {
        return orders.findByPublicToken(po.getPublicToken()).orElseThrow().getStatus();
    }

    // ---------- 权限 ----------

    @Test
    @DisplayName("🔒 无订单权限的 STAFF 访问进货单列表 → 403")
    void withoutOrderPermissionForbidden() throws Exception {
        mvc.perform(get(BASE).with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("🔒 只有 order_view 的账号不能录入包裹 / 标记送达 → 403")
    void viewOnlyCannotWrite() throws Exception {
        PurchaseOrder po = paidPo();
        Authentication viewer = staffWith(AdminPermissions.SHOP_ORDER_VIEW);
        mvc.perform(post(BASE + "/{t}/shipments", po.getPublicToken())
                        .with(authentication(viewer)).with(csrf())
                        .param("carrier", "JNE REG").param("trackingNo", "JP1"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/{t}/mark-delivered", po.getPublicToken())
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(statusOf(po)).isEqualTo(PurchaseOrderStatus.PAID);
    }

    // ---------- 列表 / 详情 ----------

    @Test
    @DisplayName("🔒 列表按状态筛出该单，且不含收货人 / 电话 / 地址")
    void listShowsRowWithoutPii() throws Exception {
        PurchaseOrder po = paidPo();
        String html = mvc.perform(get(BASE).param("status", "PAID").param("q", po.getOrderNo())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(po.getOrderNo()).contains(po.getStoreNameSnapshot());
        assertThat(html).doesNotContain(RECEIVER).doesNotContain(PHONE).doesNotContain(ADDRESS_LINE);
    }

    @Test
    @DisplayName("详情：明细 + 收货信息；只读账号看不到写表单")
    void detailRendersLinesAndShipTo() throws Exception {
        PurchaseOrder po = paidPo();
        String html = mvc.perform(get(BASE + "/{t}", po.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(po.getOrderNo()).contains("Royal Canin").contains(RECEIVER)
                .contains(ADDRESS_LINE);
        assertThat(html).doesNotContain("/shipments\"");
    }

    @Test
    @DisplayName("详情：不存在的 token → 回列表并提示，不吐 JSON")
    void unknownTokenRedirectsToList() throws Exception {
        mvc.perform(get(BASE + "/{t}", "nope")
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_VIEW))))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attributeExists("error"));
    }

    // ---------- 录入包裹 / 标记送达 ----------

    @Test
    @DisplayName("录入包裹 → SHIPPED，写审计（只记单号）；再标记送达 → DELIVERED")
    void shipThenMarkDelivered() throws Exception {
        PurchaseOrder po = paidPo();
        Authentication staff = staffWith(AdminPermissions.SHOP_ORDER_FULFILL);
        String tracking = "JP" + SEQ.incrementAndGet();

        mvc.perform(post(BASE + "/{t}/shipments", po.getPublicToken())
                        .with(authentication(staff)).with(csrf())
                        .param("carrier", "JNE REG").param("trackingNo", tracking)
                        .param("trackingUrl", "https://jne.co.id/track/" + tracking))
                .andExpect(redirectedUrl(BASE + "/" + po.getPublicToken()))
                .andExpect(flash().attributeExists("notice"));
        assertThat(statusOf(po)).isEqualTo(PurchaseOrderStatus.SHIPPED);

        String summary = jdbc.queryForObject("""
                SELECT summary FROM admin_audit_logs
                WHERE action_type = ? AND target_id = ?""",
                String.class, AuditActions.MERCHANT_PO_SHIPMENT_ADDED, po.getPublicToken());
        assertThat(summary).doesNotContain(RECEIVER).doesNotContain(PHONE).doesNotContain(ADDRESS_LINE);

        mvc.perform(post(BASE + "/{t}/mark-delivered", po.getPublicToken())
                        .with(authentication(staff)).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/" + po.getPublicToken()))
                .andExpect(flash().attributeExists("notice"));
        assertThat(statusOf(po)).isEqualTo(PurchaseOrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("查询链接不是 http/https → 不落包裹，flash 报错，状态不变")
    void invalidTrackingUrlRejected() throws Exception {
        PurchaseOrder po = paidPo();
        mvc.perform(post(BASE + "/{t}/shipments", po.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_FULFILL))).with(csrf())
                        .param("carrier", "JNE REG").param("trackingNo", "JP9")
                        .param("trackingUrl", "javascript:alert(1)"))
                .andExpect(redirectedUrl(BASE + "/" + po.getPublicToken()))
                .andExpect(flash().attributeExists("error"));
        assertThat(statusOf(po)).isEqualTo(PurchaseOrderStatus.PAID);
    }

    @Test
    @DisplayName("待发货单不能标记送达 → flash 报错，状态不变")
    void markDeliveredRequiresShipped() throws Exception {
        PurchaseOrder po = paidPo();
        mvc.perform(post(BASE + "/{t}/mark-delivered", po.getPublicToken())
                        .with(authentication(staffWith(AdminPermissions.SHOP_ORDER_FULFILL))).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        assertThat(statusOf(po)).isEqualTo(PurchaseOrderStatus.PAID);
    }
}
