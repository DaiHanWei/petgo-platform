package com.tailtopia.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
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

        long id = accountService.createAccount(email, "新人" + seq, AdminAccountType.STAFF,
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
        long id = accountService.createAccount(email, "停用测试", AdminAccountType.STAFF,
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
        long id = accountService.createAccount(email, "权限测试", AdminAccountType.STAFF,
                List.of("vet.view"), actor);

        accountService.updatePermissions(id, List.of("content.takedown", "rating.view"), actor);

        AdminUserDetails ud = userDetailsService.loadByEmail(email, false);
        assertThat(authorities(ud)).contains("content.takedown", "rating.view")
                .doesNotContain("vet.view");
    }
}
