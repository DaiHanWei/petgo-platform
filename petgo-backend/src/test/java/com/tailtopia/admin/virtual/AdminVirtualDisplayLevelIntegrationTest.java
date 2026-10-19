package com.tailtopia.admin.virtual;

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
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1：虚拟号 / 官方号展示等级（V1.4.0 ① Story 4.4 · AC1 ~ AC6）—— 需 Docker postgres+redis，<b>云端不跑，待本地验收</b>。
 */
class AdminVirtualDisplayLevelIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private AdminVirtualAccountService service;
    @Autowired
    private JdbcTemplate jdbc;

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("display-level-" + n + "@tailtopia.test",
                "展示等级运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, Set.of(codes));
        List<GrantedAuthority> auths = new ArrayList<>(p.getAuthorities());
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new TestingAuthenticationToken(p, null, auths);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, String target) {
        return b.header("HX-Request", "true").header("HX-Target", target).param("lang", "zh_CN");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void newVirtualAccountGetsRandomSixToEight() {
        long id = service.create("展示等级喵", null, 1L);
        assertThat(jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?", Integer.class, id))
                .isBetween(6, 8);
    }

    @Test
    void listColumnDrawerCardAndSaveOnlyTouchDisplayLevel() throws Exception {
        long id = service.create("展示等级汪", null, 1L);
        Authentication manager = staffWith(AdminPermissions.VIRTUAL_ACCOUNT_MANAGE);
        int max = jdbc.queryForObject("SELECT MAX(level) FROM growth_levels", Integer.class);

        String list = mvc.perform(get("/admin/virtual-accounts").with(authentication(manager)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("展示等级").contains("data-display-level");
        String drawer = mvc.perform(htmx(get("/admin/virtual-accounts/" + id + "/drawer").with(authentication(manager)),
                "virtual-drawer")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-section=\"virtual-display-level\"").contains("value=\"" + max + "\"")
                .doesNotContain("value=\"" + (max + 1) + "\"");

        long ledger = count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", id);
        long notes = count("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", id);
        long audits = count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_DISPLAY_LEVEL_CHANGED' "
                + "AND target_id = ?", String.valueOf(id));
        mvc.perform(htmx(post("/admin/virtual-accounts/" + id + "/display-level").with(authentication(manager))
                .with(csrf()).param("displayLevel", "12"), "virtual-drawer-err")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?", Integer.class, id))
                .isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", id)).isEqualTo(ledger);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", id)).isEqualTo(notes);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_DISPLAY_LEVEL_CHANGED' "
                + "AND target_id = ?", String.valueOf(id))).isEqualTo(audits + 1);

        mvc.perform(htmx(post("/admin/virtual-accounts/" + id + "/display-level").with(authentication(manager))
                .with(csrf()).param("displayLevel", String.valueOf(max + 1)), "virtual-drawer-err"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void gatesAndRealUserRejection() throws Exception {
        long id = service.create("展示等级鸟", null, 1L);
        Authentication viewer = staffWith(AdminPermissions.VIRTUAL_ACCOUNT_VIEW);
        mvc.perform(htmx(post("/admin/virtual-accounts/" + id + "/display-level").with(authentication(viewer))
                .with(csrf()).param("displayLevel", "9"), "virtual-drawer-err")).andExpect(status().isForbidden());
        String drawer = mvc.perform(htmx(get("/admin/virtual-accounts/" + id + "/drawer").with(authentication(viewer)),
                "virtual-drawer")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-display-level-current").contains("需要「管理虚拟账号」权限");

        User real = newUser();
        mvc.perform(htmx(post("/admin/virtual-accounts/" + real.getId() + "/display-level")
                .with(authentication(staffWith(AdminPermissions.VIRTUAL_ACCOUNT_MANAGE))).with(csrf())
                .param("displayLevel", "9"), "virtual-drawer-err")).andExpect(status().isUnprocessableEntity());
    }

    /**
     * P-7 官方号（2026-10-19 用户拍板落点）：在「运营发布身份」页设置，不放开用户抽屉。
     * 列表显示 → 保存生效（只写 display_level、审计 1 行）→ 无权限只读 → 真实 USER 目标 422。
     */
    @Test
    void officialAccountDisplayLevelIsEditableOnPublishIdentityPage() throws Exception {
        long n = SEQ.incrementAndGet();
        User official = users.saveAndFlush(User.newAdmin("official-dl-" + n + "@tailtopia.test", "官方号" + n, "{bcrypt}x"));
        long id = official.getId();
        Authentication manager = staffWith(AdminPermissions.VIRTUAL_ACCOUNT_MANAGE);

        String page = mvc.perform(get("/admin/virtual-accounts").with(authentication(manager)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"official-rows\"").contains("id=\"official-row-" + id + "\"")
                .contains("官方号" + n).contains("/admin/virtual-accounts/official/" + id + "/display-level")
                .contains("data-official-display-form");
        // 官方号区块只有展示等级卡：不出现虚拟号专属的启停 / 物种入口
        String block = page.substring(page.indexOf("id=\"official-rows\""), page.indexOf("id=\"official-inline-err\""));
        assertThat(block).doesNotContain("/enabled").doesNotContain("/species").doesNotContain("accountSpecies");

        long ledger = count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", id);
        long audits = count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_DISPLAY_LEVEL_CHANGED' "
                + "AND target_id = ?", String.valueOf(id));
        String saved = mvc.perform(htmx(post("/admin/virtual-accounts/official/" + id + "/display-level")
                        .with(authentication(manager)).with(csrf()).param("displayLevel", "10"), "official-inline-err"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(saved).contains("id=\"official-rows\"").contains("hx-swap-oob").contains("admin-toast-host");
        assertThat(jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?", Integer.class, id))
                .isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", id)).isEqualTo(ledger);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_DISPLAY_LEVEL_CHANGED' "
                + "AND target_id = ?", String.valueOf(id))).isEqualTo(audits + 1);

        // 无 manage：只读 + 提示，写入 403
        Authentication viewer = staffWith(AdminPermissions.VIRTUAL_ACCOUNT_VIEW);
        String readOnly = mvc.perform(get("/admin/virtual-accounts").with(authentication(viewer)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String roBlock = readOnly.substring(readOnly.indexOf("id=\"official-rows\""),
                readOnly.indexOf("id=\"official-inline-err\""));
        assertThat(roBlock).contains("id=\"official-row-" + id + "\"").contains("Lv10")
                .doesNotContain("data-official-display-form").doesNotContain("hx-post")
                .contains("需要「管理虚拟账号」权限");
        mvc.perform(htmx(post("/admin/virtual-accounts/official/" + id + "/display-level").with(authentication(viewer))
                .with(csrf()).param("displayLevel", "9"), "official-inline-err")).andExpect(status().isForbidden());

        // 真实 USER 目标 → 422；越界 → 422
        User real = newUser();
        mvc.perform(htmx(post("/admin/virtual-accounts/official/" + real.getId() + "/display-level")
                .with(authentication(manager)).with(csrf()).param("displayLevel", "9"), "official-inline-err"))
                .andExpect(status().isUnprocessableEntity());
        int max = jdbc.queryForObject("SELECT MAX(level) FROM growth_levels", Integer.class);
        mvc.perform(htmx(post("/admin/virtual-accounts/official/" + id + "/display-level")
                .with(authentication(manager)).with(csrf()).param("displayLevel", String.valueOf(max + 1)),
                "official-inline-err")).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?", Integer.class, id))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_accounts WHERE user_id = ?", Long.class,
                real.getId())).isZero();
    }

    /** 注销已提交的官方号（造 DONE 行，免得被注销扫描器真去级联）：列表不再出现、写入 422（D1/D2：级联进行中不补建 growth_accounts 行）。 */
    @Test
    void officialWithDeletionSubmittedIsHiddenAndRejected() throws Exception {
        long n = SEQ.incrementAndGet();
        User official = users.saveAndFlush(User.newAdmin("official-del-" + n + "@tailtopia.test", "注销官方号" + n,
                "{bcrypt}x"));
        jdbc.update("INSERT INTO account_deletions (user_id, status, requested_at) VALUES (?, 'DONE', now())",
                official.getId());
        Authentication manager = staffWith(AdminPermissions.VIRTUAL_ACCOUNT_MANAGE);
        String page = mvc.perform(get("/admin/virtual-accounts").with(authentication(manager)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("id=\"official-row-" + official.getId() + "\"");
        mvc.perform(htmx(post("/admin/virtual-accounts/official/" + official.getId() + "/display-level")
                .with(authentication(manager)).with(csrf()).param("displayLevel", "9"), "official-inline-err"))
                .andExpect(status().isUnprocessableEntity());
    }
}
