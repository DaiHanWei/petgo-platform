package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.content.service.VideoTrustedPublisherService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Postgres + Redis）：专题线 video Story 1.1 —— 后台设置会员等级与视频免审名单，{@code /me} 下发 vipLevel。
 */
class AdminUserVideoPermissionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private AdminAuditService auditService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VideoTrustedPublisherService trustedPort;
    @Autowired
    private VideoTrustedPublisherAdminService trustedAdmin;

    /** STAFF principal 显式声明权限码（不让超管隐式全权把权限断言变成恒真）。 */
    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "video-perm-" + n + "@tailtopia.test", "视频权限测试" + n, "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private static long actorId(Authentication a) {
        return ((AdminUserDetails) a.getPrincipal()).getAdminAccountId();
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b) {
        return b.header("HX-Request", "true").header("HX-Target", "user-drawer-err");
    }

    private int levelInDb(long userId) {
        return jdbc.queryForObject("SELECT vip_level FROM users WHERE id = ?", Integer.class, userId);
    }

    private List<AdminAuditLog> audits(Authentication actor, String action) {
        return auditService.search(null, null, actorId(actor), action, PageRequest.of(0, 5)).getContent();
    }

    @Test
    @DisplayName("AC1：新用户 vip_level 默认 1；约束拒绝 0 与 100；名单表存在")
    void migrationDefaultsAndConstraints() {
        User u = newUser();
        assertThat(levelInDb(u.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'vip_level'",
                String.class)).isEqualTo("integer");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'video_trusted_publishers'",
                Integer.class)).isEqualTo(1);
        for (int bad : new int[] {0, 100}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    jdbc.update("UPDATE users SET vip_level = ? WHERE id = ?", bad, u.getId()))
                    .hasMessageContaining("ck_users_vip_level");
        }
    }

    @Test
    @DisplayName("AC2：有权限改等级为 10 → 生效 + 审计 vip_level 1→10")
    void setVipLevelWithPermission() throws Exception {
        User u = newUser();
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.USER_VIP_LEVEL_EDIT);
        mvc.perform(htmx(post("/admin/users/{id}/vip-level", u.getId()).param("level", "10"))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
        assertThat(levelInDb(u.getId())).isEqualTo(10);
        List<AdminAuditLog> logs = audits(actor, AuditActions.USER_VIP_LEVEL_CHANGE);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getSummary()).isEqualTo("vip_level 1→10");
        assertThat(logs.get(0).getTargetId()).isEqualTo(String.valueOf(u.getId()));
    }

    @Test
    @DisplayName("AC2：0 / 100 / 非数字 → 校验错误，等级不变、不写审计")
    void rejectsOutOfRange() throws Exception {
        User u = newUser();
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.USER_VIP_LEVEL_EDIT);
        for (String bad : new String[] {"0", "100", "abc"}) {
            mvc.perform(htmx(post("/admin/users/{id}/vip-level", u.getId()).param("level", bad))
                            .with(csrf()).with(authentication(actor)))
                    .andExpect(status().is4xxClientError());
        }
        assertThat(levelInDb(u.getId())).isEqualTo(1);
        assertThat(audits(actor, AuditActions.USER_VIP_LEVEL_CHANGE)).isEmpty();
    }

    @Test
    @DisplayName("AC2：已注销账号不可改等级")
    void rejectsDeletedAccount() throws Exception {
        User u = newUser();
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", u.getId());
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.USER_VIP_LEVEL_EDIT);
        mvc.perform(htmx(post("/admin/users/{id}/vip-level", u.getId()).param("level", "10"))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().is4xxClientError());
        assertThat(levelInDb(u.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC3：无 user.vip_level.edit → 403；抽屉只渲染禁用按钮")
    void vipLevelForbiddenWithoutPermission() throws Exception {
        User u = newUser();
        Authentication viewer = staffWith(AdminPermissions.USER_VIEW);
        mvc.perform(htmx(post("/admin/users/{id}/vip-level", u.getId()).param("level", "10"))
                        .with(csrf()).with(authentication(viewer)))
                .andExpect(status().isForbidden());
        assertThat(levelInDb(u.getId())).isEqualTo(1);

        String drawer = mvc.perform(htmx(get("/admin/users/{id}/drawer", u.getId())).with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("/vip-level");
        assertThat(drawer).doesNotContain("/video-trusted");
    }

    @Test
    @DisplayName("AC4：加入 / 移出名单 + 审计；重复操作幂等")
    void addAndRemoveTrusted() throws Exception {
        User u = newUser();
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.VIDEO_TRUSTED_MANAGE);
        for (int i = 0; i < 2; i++) {
            mvc.perform(htmx(post("/admin/users/{id}/video-trusted", u.getId()).param("action", "add"))
                            .with(csrf()).with(authentication(actor)))
                    .andExpect(status().isOk());
        }
        assertThat(trustedPort.isTrusted(u.getId())).isTrue();
        assertThat(audits(actor, AuditActions.VIDEO_TRUSTED_ADD)).hasSize(1);

        for (int i = 0; i < 2; i++) {
            mvc.perform(htmx(post("/admin/users/{id}/video-trusted", u.getId()).param("action", "remove"))
                            .with(csrf()).with(authentication(actor)))
                    .andExpect(status().isOk());
        }
        assertThat(trustedPort.isTrusted(u.getId())).isFalse();
        assertThat(audits(actor, AuditActions.VIDEO_TRUSTED_REMOVE)).hasSize(1);
    }

    @Test
    @DisplayName("AC4：无 video.trusted.manage → 403")
    void trustedForbiddenWithoutPermission() throws Exception {
        User u = newUser();
        mvc.perform(htmx(post("/admin/users/{id}/video-trusted", u.getId()).param("action", "add"))
                        .with(csrf()).with(authentication(staffWith(AdminPermissions.USER_VIEW))))
                .andExpect(status().isForbidden());
        assertThat(trustedPort.isTrusted(u.getId())).isFalse();
    }

    @Test
    @DisplayName("AC5：名单页有权限 200 并显示等级；无权限 403")
    void trustedListPage() throws Exception {
        User u = newUser();
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.VIDEO_TRUSTED_MANAGE,
                AdminPermissions.USER_VIP_LEVEL_EDIT);
        mvc.perform(htmx(post("/admin/users/{id}/vip-level", u.getId()).param("level", "10"))
                .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        mvc.perform(htmx(post("/admin/users/{id}/video-trusted", u.getId()).param("action", "add"))
                .with(csrf()).with(authentication(actor))).andExpect(status().isOk());

        String page = mvc.perform(get("/admin/video-trusted").with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("video-trusted-rows").contains(u.getNickname());

        mvc.perform(get("/admin/video-trusted").with(authentication(staffWith(AdminPermissions.USER_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC7：/me 返回 vipLevel")
    void meReturnsVipLevel() throws Exception {
        User u = newUser();
        jdbc.update("UPDATE users SET vip_level = 10 WHERE id = ?", u.getId());
        JsonNode body = json.readTree(mvc.perform(get("/api/v1/me")
                        .header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(body.get("vipLevel").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("AC8：后台改等级后，用户整行 save 的 PATCH /me 不会把等级写回旧值")
    void patchMeDoesNotOverwriteVipLevel() throws Exception {
        User u = newUser();
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.USER_VIP_LEVEL_EDIT);
        mvc.perform(htmx(post("/admin/users/{id}/vip-level", u.getId()).param("level", "10"))
                .with(csrf()).with(authentication(actor))).andExpect(status().isOk());

        mvc.perform(patch("/api/v1/me").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petStatus\":\"HAS_PET\"}"))
                .andExpect(status().isOk());
        assertThat(levelInDb(u.getId())).isEqualTo(10);
    }

    @Test
    @DisplayName("评审 #1：名单里已有该行（模拟并发另一方先提交）时再加入 → 200 + 提示已在名单，不报 500、不写审计")
    void addWhenRowAlreadyExistsIsIdempotentWithout500() throws Exception {
        User u = newUser();
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.VIDEO_TRUSTED_MANAGE);
        jdbc.update("INSERT INTO video_trusted_publishers (user_id, created_by_admin_id) VALUES (?, ?)",
                u.getId(), actorId(actor));
        mvc.perform(htmx(post("/admin/users/{id}/video-trusted", u.getId()).param("action", "add"))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
        assertThat(audits(actor, AuditActions.VIDEO_TRUSTED_ADD)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_trusted_publishers WHERE user_id = ?",
                Integer.class, u.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("评审 #2：注销级联把账号移出名单（users 行不删，FK CASCADE 不触发）；可重复执行")
    void accountDeletionPurgesTrustedRow() throws Exception {
        User u = newUser();
        Authentication actor = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.VIDEO_TRUSTED_MANAGE);
        mvc.perform(htmx(post("/admin/users/{id}/video-trusted", u.getId()).param("action", "add"))
                .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        assertThat(trustedPort.isTrusted(u.getId())).isTrue();
        trustedAdmin.purgeForAccountDeletion(u.getId());
        trustedAdmin.purgeForAccountDeletion(u.getId());
        assertThat(trustedPort.isTrusted(u.getId())).isFalse();
    }

    @Test
    @DisplayName("评审 #6/#7：无 user.view 时昵称不是链接；越界页码不显示「名单为空」")
    void listPageLinkGatingAndOutOfRangePage() throws Exception {
        User u = newUser();
        Authentication full = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.VIDEO_TRUSTED_MANAGE);
        mvc.perform(htmx(post("/admin/users/{id}/video-trusted", u.getId()).param("action", "add"))
                .with(csrf()).with(authentication(full))).andExpect(status().isOk());

        String withLink = mvc.perform(get("/admin/video-trusted").with(authentication(full)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(withLink).contains("/admin/users?open=" + u.getId());

        Authentication listOnly = staffWith(AdminPermissions.VIDEO_TRUSTED_MANAGE);
        String noLink = mvc.perform(get("/admin/video-trusted").with(authentication(listOnly)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(noLink).doesNotContain("/admin/users?open=" + u.getId());

        String outOfRange = mvc.perform(get("/admin/video-trusted").param("page", "999").with(authentication(full)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(outOfRange).doesNotContain(org.springframework.web.util.HtmlUtils.htmlEscape("名单为空"))
                .doesNotContain("The list is empty");
    }
}
