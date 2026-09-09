package com.tailtopia.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * L1：账号管理端到端（需 Docker postgres）。验证 admin_account_permissions validate 绿、
 * 创建 STAFF → 其权限码装载为 authority（AC2/AC6 闭环）、停用即拒登（A1/AC5）、激活恢复、写审计（AC3/AC5）。
 *
 * <p>注：持久卷里 SUPER_ADMIN 跨运行累积，故不在 L1 断言绝对超管计数；上限 5（AC4）由 L0 mock 覆盖。
 */
class AdminAccountManagementIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private AdminAuditService auditService;

    private static List<String> authorities(AdminUserDetails ud) {
        return ud.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
    }

    @Test
    void createStaffLoadsPermissionAuthoritiesAndAudits() {
        long seq = SEQ.incrementAndGet();
        String email = "staff-" + seq + "@tailtopia.test";
        long actor = 100000L + seq;

        long id = accountService.createAccount(email, "新人" + seq, AdminRole.CUSTOM,
                List.of("vet.view", "admin.view_logs"), actor);
        assertThat(id).isPositive();

        // Lark 登录路径（无密码）装载 authorities：含模块权限码（AC2/AC6 与 1.3 view_logs 闭环）。
        AdminUserDetails ud = userDetailsService.loadByEmail(email, false);
        assertThat(authorities(ud)).contains("ROLE_ADMIN", "vet.view", "admin.view_logs")
                .doesNotContain("ROLE_SUPER_ADMIN");

        // 写审计 ACCOUNT_CREATED（含邮箱摘要）。
        List<AdminAuditLog> audits = auditService.search(null, null, actor,
                AuditActions.ACCOUNT_CREATED, PageRequest.of(0, 10)).getContent();
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).getSummary()).contains(email);
    }

    @Test
    void deactivateRejectsLoginThenReactivateRestores() {
        long seq = SEQ.incrementAndGet();
        String email = "staff-deac-" + seq + "@tailtopia.test";
        long actor = 200000L + seq;
        long id = accountService.createAccount(email, "停用测试", AdminRole.CUSTOM,
                List.of("vet.view"), actor);

        accountService.deactivate(id, actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getStatus())
                .isEqualTo(AdminAccountStatus.DISABLED);
        // A1：停用账号不可再加载登录主体（会话守卫每请求复查同理）。
        assertThatThrownBy(() -> userDetailsService.loadByEmail(email, false))
                .isInstanceOf(UsernameNotFoundException.class);

        accountService.reactivate(id, actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getStatus())
                .isEqualTo(AdminAccountStatus.ACTIVE);
        // 恢复后可再加载。
        assertThat(userDetailsService.loadByEmail(email, false).getUsername()).isEqualTo(email);
    }

    @Test
    void updatePermissionsChangesLoadedAuthorities() {
        long seq = SEQ.incrementAndGet();
        String email = "staff-perm-" + seq + "@tailtopia.test";
        long actor = 300000L + seq;
        long id = accountService.createAccount(email, "权限测试", AdminRole.CUSTOM,
                List.of("vet.view"), actor);

        accountService.updatePermissions(id, List.of("content.takedown", "rating.view"), actor);

        AdminUserDetails ud = userDetailsService.loadByEmail(email, false);
        assertThat(authorities(ud)).contains("content.takedown", "rating.view")
                .doesNotContain("vet.view");
    }

    // ---- V1.3.0 Story 1.1（AD-1）：安全版本号真库闭环 ----

    @Test
    void securityVersionBumpsOnRealChangesOnlyAndSnapshotsIntoPrincipal() {
        long seq = SEQ.incrementAndGet();
        String email = "staff-secver-" + seq + "@tailtopia.test";
        long actor = 400000L + seq;
        long id = accountService.createAccount(email, "版本号测试", AdminRole.CUSTOM,
                List.of("vet.view"), actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isZero();

        // updatePermissions 真变 +1；无 diff 不加。
        accountService.updatePermissions(id, List.of("content.takedown"), actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(1);
        accountService.updatePermissions(id, List.of("content.takedown"), actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(1);

        // changeRole 真变 +1；同角色幂等不加。
        accountService.changeRole(id, AdminRole.OPERATIONS, actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(2);
        accountService.changeRole(id, AdminRole.OPERATIONS, actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(2);

        // AC3：登录时 principal 快照 = DB 值。
        assertThat(userDetailsService.loadByEmail(email, false).getSecurityVersion()).isEqualTo(2);

        // deactivate 真变 +1；重复停用不加；reactivate 不加。
        accountService.deactivate(id, actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(3);
        accountService.deactivate(id, actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(3);
        accountService.reactivate(id, actor);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(3);

        // 公开出口 bumpSecurityVersion（供 1.3 / 1.5 调用）。
        accountService.bumpSecurityVersion(id);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(4);
        assertThat(userDetailsService.loadByEmail(email, false).getSecurityVersion()).isEqualTo(4);
    }

    // ---- V1.3.0 Story 1.2：改名真库闭环 ----

    @Test
    void renameShowsInListAuditsAndKeepsSecurityVersion() {
        long seq = SEQ.incrementAndGet();
        String email = "staff-rename-" + seq + "@tailtopia.test";
        long actor = 500000L + seq;
        long id = accountService.createAccount(email, "旧名", AdminRole.CUSTOM, List.of("vet.view"), actor);

        accountService.rename(id, "新名" + seq, actor);

        assertThat(accountService.list().stream().filter(v -> v.id() == id).findFirst().orElseThrow()
                .displayName()).isEqualTo("新名" + seq);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isZero();
        List<AdminAuditLog> audits = auditService.search(null, null, actor,
                AuditActions.ACCOUNT_RENAMED, PageRequest.of(0, 10)).getContent();
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).getSummary()).contains("旧名").contains("新名" + seq);
    }

    // ---- V1.3.0 Story 1.3：换绑邮箱真库闭环 + 部分唯一索引 ----

    @Test
    void rebindEmailMovesIdentityKeepsPermissionsBumpsVersionAndAudits() {
        long seq = SEQ.incrementAndGet();
        String oldEmail = "staff-rebind-old-" + seq + "@tailtopia.test";
        String newEmail = "staff-rebind-new-" + seq + "@tailtopia.test";
        long actor = 600000L + seq;
        long id = accountService.createAccount(oldEmail, "换绑测试", AdminRole.CUSTOM,
                List.of("vet.view", "admin.view_logs"), actor);
        List<String> before = authorities(userDetailsService.loadByEmail(oldEmail, false));

        accountService.rebindEmail(id, newEmail, actor);

        assertThatThrownBy(() -> userDetailsService.loadByEmail(oldEmail, false))
                .isInstanceOf(UsernameNotFoundException.class);
        AdminUserDetails ud = userDetailsService.loadByEmail(newEmail, false);
        assertThat(ud.getAdminAccountId()).isEqualTo(id);
        assertThat(authorities(ud)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(adminAccounts.findById(id).orElseThrow().getSecurityVersion()).isEqualTo(1);
        List<AdminAuditLog> audits = auditService.search(null, null, actor,
                AuditActions.ACCOUNT_EMAIL_REBOUND, PageRequest.of(0, 10)).getContent();
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).getSummary()).contains(oldEmail).contains(newEmail);
    }

    @Test
    void disabledEmailCanBeReusedButActiveDuplicateRejected() {
        long seq = SEQ.incrementAndGet();
        String email = "staff-reuse-" + seq + "@tailtopia.test";
        long actor = 700000L + seq;
        long a = accountService.createAccount(email, "A", AdminRole.CUSTOM, List.of(), actor);
        accountService.deactivate(a, actor);
        // D-21 + 部分唯一索引：停用后邮箱释放，B 可建。
        long b = accountService.createAccount(email, "B", AdminRole.CUSTOM, List.of(), actor);
        assertThat(b).isNotEqualTo(a);
        // 再建 C（ACTIVE 重复）被服务层拒。
        assertThatThrownBy(() -> accountService.createAccount(email, "C", AdminRole.CUSTOM, List.of(), actor))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        // 登录白名单只命中 ACTIVE 的 B。
        assertThat(userDetailsService.loadByEmail(email, false).getAdminAccountId()).isEqualTo(b);
    }

    // ---- V1.3.0 Story 1.4：四岗位登录权限迁移前后逐位相等 + role_id 设/清 ----

    @Test
    void migratedRolesLoadSnapshotAuthoritiesAndRoleIdFollowsRole() {
        long seq = SEQ.incrementAndGet();
        long actor = 800000L + seq;
        for (var e : com.tailtopia.admin.roles.AdminRoleSeedSnapshot.MIGRATED.entrySet()) {
            String email = "staff-" + e.getKey().name().toLowerCase() + "-" + seq + "@tailtopia.test";
            long id = accountService.createAccount(email, "岗位" + seq, e.getKey(), List.of(), actor);
            assertThat(adminAccounts.findById(id).orElseThrow().getRoleId()).isNotNull();
            List<String> auth = authorities(userDetailsService.loadByEmail(email, false));
            assertThat(auth).as(e.getKey() + " 登录权限 ≠ 迁移前快照")
                    .containsExactlyInAnyOrderElementsOf(
                            java.util.stream.Stream.concat(java.util.stream.Stream.of("ROLE_ADMIN"),
                                    e.getValue().stream()).toList());
        }
        // OPS_MANAGER 仍读枚举、role_id NULL；切到 CUSTOM 清 role_id 并 carry。
        String mgr = "staff-mgr-" + seq + "@tailtopia.test";
        long mgrId = accountService.createAccount(mgr, "主管" + seq, AdminRole.OPS_MANAGER, List.of(), actor);
        assertThat(adminAccounts.findById(mgrId).orElseThrow().getRoleId()).isNull();
        assertThat(authorities(userDetailsService.loadByEmail(mgr, false)))
                .containsAll(AdminRole.OPS_MANAGER.permissionCodes());
        accountService.changeRole(mgrId, AdminRole.FINANCE, actor);
        assertThat(adminAccounts.findById(mgrId).orElseThrow().getRoleId()).isNotNull();
        accountService.changeRole(mgrId, AdminRole.CUSTOM, actor);
        assertThat(adminAccounts.findById(mgrId).orElseThrow().getRoleId()).isNull();
        assertThat(authorities(userDetailsService.loadByEmail(mgr, false)))
                .containsAll(com.tailtopia.admin.roles.AdminRoleSeedSnapshot.FINANCE);
    }
}
