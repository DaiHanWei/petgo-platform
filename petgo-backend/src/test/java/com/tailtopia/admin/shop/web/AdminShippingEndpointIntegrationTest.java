package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1 集成：D1 服务范围与运费配置（V1.3.0 Story 10.6，模板 D 三卡）。
 *
 * <p>本 story 是<b>壳层重构</b>：端点路径 / 参数 / 权限一字未改，新增的只是四个 POST 的 htmx 分支。
 * 所以这里钉的是三件事 —— ① htmx 成功回<b>该卡</b>（带 {@code HX-Retarget} / {@code HX-Reswap}）、
 * ② 失败落<b>卡自己的</b> err 槽、③ 非 htmx 的 PRG 与权限一如既往。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis（{@code ApiIntegrationTest} 无 Testcontainers）。
 */
class AdminShippingEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100000);

    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "ship-" + n + "@tailtopia.test", "运费测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    private String seedZone() {
        String kec = "Kec" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shipping_zones (kecamatan, kota_kabupaten, provinsi, fee, active)
                VALUES (?, 'Jakarta Selatan', 'DKI Jakarta', 20000, true)
                """, kec);
        return kec;
    }

    private boolean activeOf(String kecamatan) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM shipping_zones WHERE kecamatan = ?", Boolean.class, kecamatan));
    }

    // ---------- AC1：整页三卡 ----------

    @Test
    @DisplayName("D1 整页 200：三张卡各有自己的保存钮与 err 槽")
    void pageRendersThreeCards() throws Exception {
        seedZone();

        String html = mvc.perform(get("/admin/shop/shipping")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_EDIT))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("cfg-ship-zones")
                .contains("cfg-ship-threshold").contains("cfg-ship-return-address");
        // 每卡自带 err 槽：422 要落在改错的那张卡里，不能都挤到页面顶端一条横幅
        assertThat(html).contains("cfg-ship-threshold-err").contains("cfg-ship-return-address-err");
        assertThat(html).doesNotContain("??admin.");
        // 🔴 界面上不得出现「配送方式」维度（C-14 已把二维运费表降为一维，只剩 Reguler）
        assertThat(html).doesNotContain("Reguler");
    }

    /**
     * 🔒 只有 {@code config.view} 的账号：看得到三张卡，但输入禁用、保存钮不渲染，
     * 且区域行上**没有启停按钮**（启停是写操作）。
     */
    @Test
    @DisplayName("🔒 D1 只读账号：卡渲染但保存钮与启停按钮都不给")
    void readOnlyAccountGetsNoSaveOrToggleButtons() throws Exception {
        seedZone();

        String html = mvc.perform(get("/admin/shop/shipping")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("cfg-ship-zones");
        assertThat(html).as("🔒 只读账号不该拿到任何写入口（服务端 @PreAuthorize 照旧兜底）")
                .doesNotContain("data-save");
        assertThat(html).as("新增区域那一块整块不渲染").doesNotContain("ship-zones-new");
    }

    // ---------- AC1：htmx 保存成功回该卡 ----------

    @Test
    @DisplayName("D1 免运门槛 htmx 保存：HX-Retarget 该卡 + HX-Reswap outerHTML + toast")
    void thresholdSaveUnderHtmxReturnsTheCard() throws Exception {
        MvcResult res = mvc.perform(post("/admin/shop/shipping/threshold")
                        .header("HX-Request", "true").header("HX-Target", "cfg-ship-threshold-err")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_EDIT))).with(csrf())
                        .param("threshold", "150000"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();

        assertThat(body).doesNotContain("<html");
        // 🔴 必须换整张卡：不换的话「已修改」标还挂着、保存钮还是激活的，运营会以为没存上再点一次
        assertThat(res.getResponse().getHeader("HX-Retarget")).isEqualTo("#cfg-ship-threshold");
        assertThat(res.getResponse().getHeader("HX-Reswap")).isEqualTo("outerHTML");
        assertThat(body).contains("data-config-card").contains("150000");
        assertThat(body).as("toast 要多包一层：beforeend oob 搬的是子节点")
                .contains("beforeend:#admin-toast-host").contains("class=\"toast\"");
    }

    /**
     * AC1：区域行是<b>一行一次 upsert</b>，成功后整张区域卡重渲染 ——
     * 新增的那一行要出现在表里，新增表单要清空。
     */
    @Test
    @DisplayName("D1 新增区域 htmx：整张区域卡重渲染，新行出现在表里")
    void zoneUpsertUnderHtmxRerendersTheWholeZonesCard() throws Exception {
        String kec = "Kec" + SEQ.incrementAndGet();

        MvcResult res = mvc.perform(post("/admin/shop/shipping/zones")
                        .header("HX-Request", "true").header("HX-Target", "cfg-ship-zone-err-new")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_EDIT))).with(csrf())
                        .param("kecamatan", kec).param("kotaKabupaten", "Jakarta Barat")
                        .param("provinsi", "DKI Jakarta").param("fee", "25000"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getResponse().getHeader("HX-Retarget")).isEqualTo("#cfg-ship-zones");
        assertThat(res.getResponse().getContentAsString()).contains(kec).contains("25000");
        assertThat(jdbc.queryForObject("SELECT fee FROM shipping_zones WHERE kecamatan = ?",
                Long.class, kec)).isEqualTo(25000L);
    }

    /**
     * AC1：启停走<b>另一个端点</b>、即时提交、不受卡保存钮控制。
     *
     * <p>🔴 这也是本页形状的由来：启停 form 与行的 upsert form 必须<b>平级</b>。
     * 嵌进去的话浏览器会静默丢掉内层 —— 点「停用」会被提交成一次 upsert，
     * 区域没停，运费反而可能被改掉（守门见 {@code AdminNoNestedFormTest}）。
     */
    @Test
    @DisplayName("D1 停用区域 htmx：即时生效，且是 active=false 不是删行")
    void toggleDisablesWithoutDeletingTheRow() throws Exception {
        String kec = seedZone();

        mvc.perform(post("/admin/shop/shipping/zones/toggle")
                        .header("HX-Request", "true").header("HX-Target", "cfg-ship-zone-err-0")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_EDIT))).with(csrf())
                        .param("kecamatan", kec).param("active", "false"))
                .andExpect(status().isOk());

        assertThat(activeOf(kec)).isFalse();
        // 🔴 停用不删行：历史订单的运费需要可追溯（AB-13D 对账）
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipping_zones WHERE kecamatan = ?",
                Integer.class, kec)).isEqualTo(1);
    }

    // ---------- AC1：失败落卡自己的 err 槽 ----------

    /**
     * 🔴 退货地址「三项要么都填、要么都留空」：只填一半的地址寄不到，
     * 而寄不到的退货会变成「货在路上、钱也没退」的双输。
     */
    @Test
    @DisplayName("D1 退货地址只填一半 → 4xx 且 HX-Retarget 落该卡的 err 槽")
    void partialReturnAddressIsRejectedIntoTheCardsErrorSlot() throws Exception {
        MvcResult res = mvc.perform(post("/admin/shop/shipping/return-address")
                        .header("HX-Request", "true")
                        .header("HX-Target", "cfg-ship-return-address-err")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_EDIT))).with(csrf())
                        .param("receiverName", "Budi")
                        .param("receiverPhone", "").param("addressText", ""))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isBetween(400, 499);
        assertThat(res.getResponse().getHeader("HX-Retarget"))
                .as("422 要落在改错的那张卡里，不是页面顶端一条横幅、更不是把整页换掉")
                .isEqualTo("#cfg-ship-return-address-err");
    }

    // ---------- AC3：权限与 PRG 一如既往 ----------

    @Test
    @DisplayName("🔒 D1 无 config.edit 的账号写入 → 403（四个端点权限零变更）")
    void writesRequireConfigEdit() throws Exception {
        Authentication viewer = staffWith(AdminPermissions.CONFIG_VIEW);

        mvc.perform(post("/admin/shop/shipping/threshold").with(authentication(viewer)).with(csrf())
                        .param("threshold", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/shop/shipping/zones").with(authentication(viewer)).with(csrf())
                        .param("kecamatan", "X").param("kotaKabupaten", "Y")
                        .param("provinsi", "Z").param("fee", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("D1 非 htmx 提交仍是 PRG：302 回本页 + flash（AC1「PRG」，一字未改）")
    void nonHtmxSubmitStillRedirects() throws Exception {
        mvc.perform(post("/admin/shop/shipping/threshold")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_EDIT))).with(csrf())
                        .param("threshold", "99000"))
                .andExpect(status().is3xxRedirection());
    }
}
