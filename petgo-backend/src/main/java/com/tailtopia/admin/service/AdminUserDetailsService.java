package com.tailtopia.admin.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.roles.service.RolePermissionResolver;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.repository.UserRepository;
import java.util.Set;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台登录的用户加载（Story 1.1 重构：数据源从 {@code users(role=ADMIN)} 迁至专用 {@code admin_accounts}）。
 *
 * <p>按 {@code lark_email} 取账号，要求 {@code status=ACTIVE} 且 {@code password_hash != null}
 * （紧急账密入口；Lark OAuth 账号无密码、走 1.2 的 OAuth 流，不经此密码加载器）。
 * App 用户 / 兽医 / 已停用 / 未设密码账号一律不可经此登录后台。
 *
 * <p>同时解析「官方内容作者」{@code users.id}（按同邮箱的 {@code role=ADMIN} 行）注入 {@code AdminUserDetails}，
 * 使既有种子发帖（{@code content_posts.author_id} 有 FK→users）等写入路径不被本次账号隔离破坏（AC5）。
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminAccountRepository adminAccounts;
    private final UserRepository users;
    /** 权限来源唯一出口（V1.3.0 Story 1.4）：枚举 / 角色表 / 账号级勾选行三处真相收拢在这里。 */
    private final RolePermissionResolver resolver;

    public AdminUserDetailsService(AdminAccountRepository adminAccounts, UserRepository users,
            RolePermissionResolver resolver) {
        this.adminAccounts = adminAccounts;
        this.users = users;
        this.resolver = resolver;
    }

    /** 紧急账密表单登录入口（Story 1.1）：要求账号已设密码。 */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return loadByEmail(username, true);
    }

    /**
     * 按 Lark 邮箱加载后台登录主体（Story 1.2 抽出，供密码登录与 Lark OAuth 共用，DRY）。
     *
     * @param requirePassword 紧急账密登录传 {@code true}（须有 password_hash）；Lark OAuth 传 {@code false}
     *        （OAuth 账号可无密码）。两路径都要求 {@code status=ACTIVE}（白名单语义）。
     */
    @Transactional(readOnly = true)
    public AdminUserDetails loadByEmail(String email, boolean requirePassword) throws UsernameNotFoundException {
        // V1.3.0 Story 1.3：邮箱仅 ACTIVE 唯一（D-21），白名单查询直接按 ACTIVE 口径（语义不变，零多行风险）。
        AdminAccount a = adminAccounts.findByLarkEmailIgnoreCaseAndStatus(email, AdminAccountStatus.ACTIVE)
                .filter(acc -> !requirePassword || acc.getPasswordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("后台账号不存在或不可登录"));
        // 官方内容作者 shim：同邮箱的 users(role=ADMIN) 行 id（AC5，保住种子发帖 author_id 的 FK 语义）。
        Long operatorUserId = users.findByEmailAndRole(a.getLarkEmail(), Role.ADMIN)
                .map(u -> u.getId())
                .orElse(null);
        Set<String> permissionCodes = resolver.resolve(a);
        // AD-1：登录时刻的安全版本号随 principal 进会话，供 AdminSessionGuardFilter 每请求比对。
        return new AdminUserDetails(a.getId(), operatorUserId, a.getLarkEmail(),
                a.getPasswordHash(), a.getAccountType(), permissionCodes, a.getSecurityVersion(),
                a.getDisplayName(), a.getRole() == null ? null : a.getRole().name());
    }
}
