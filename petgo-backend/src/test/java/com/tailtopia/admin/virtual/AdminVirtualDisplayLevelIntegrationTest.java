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
        long notes = count("SELECT COUNT(*) FROM notifications WHERE user_id = ?", id);
        long audits = count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_DISPLAY_LEVEL_CHANGED' "
                + "AND target_id = ?", String.valueOf(id));
        mvc.perform(htmx(post("/admin/virtual-accounts/" + id + "/display-level").with(authentication(manager))
                .with(csrf()).param("displayLevel", "12"), "virtual-drawer-err")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?", Integer.class, id))
                .isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", id)).isEqualTo(ledger);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id = ?", id)).isEqualTo(notes);
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

    @Test
    void officialAccountDisplayLevelIsEditableInUserDrawer() throws Exception {
        User official = newUser();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", official.getId());
        Authentication op = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.VIRTUAL_ACCOUNT_MANAGE);

        String html = mvc.perform(htmx(get("/admin/users/" + official.getId() + "/drawer").with(authentication(op)),
                "user-drawer")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-growth-state=\"OFFICIAL\"").contains("data-growth-display-form");
        mvc.perform(htmx(post("/admin/users/" + official.getId() + "/growth/display-level").with(authentication(op))
                .with(csrf()).param("displayLevel", "10"), "user-drawer-err")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?", Integer.class,
                official.getId())).isEqualTo(10);
    }
}
