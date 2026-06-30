package com.tailtopia.admin.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.repository.UserRepository;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final AdminAccountPermissionRepository permissions;

    public AdminUserDetailsService(AdminAccountRepository adminAccounts, UserRepository users,
            AdminAccountPermissionRepository permissions) {
        this.adminAccounts = adminAccounts;
        this.users = users;
        this.permissions = permissions;
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
        AdminAccount a = adminAccounts.findByLarkEmail(email)
                .filter(acc -> acc.getStatus() == AdminAccountStatus.ACTIVE)
                .filter(acc -> !requirePassword || acc.getPasswordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("后台账号不存在或不可登录"));
        // 官方内容作者 shim：同邮箱的 users(role=ADMIN) 行 id（AC5，保住种子发帖 author_id 的 FK 语义）。
        Long operatorUserId = users.findByEmailAndRole(a.getLarkEmail(), Role.ADMIN)
                .map(u -> u.getId())
                .orElse(null);
        // Story 1.5：STAFF 装载其模块权限码为 authority；SUPER_ADMIN 隐式全权（经 hasRole('SUPER_ADMIN')
        // 表达式判定，不注入全集——新增权限码无需同步，抗遗漏），故此处不为其查权限表。
        Set<String> permissionCodes = a.getAccountType() == AdminAccountType.SUPER_ADMIN
                ? Set.of()
                : permissions.findByAccountId(a.getId()).stream()
                        .map(AdminAccountPermission::getPermissionCode)
                        .collect(Collectors.toSet());
        return new AdminUserDetails(a.getId(), operatorUserId, a.getLarkEmail(),
                a.getPasswordHash(), a.getAccountType(), permissionCodes);
    }
}
