package com.tailtopia.admin.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminRole;
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
        Set<String> permissionCodes = resolvePermissions(a);
        return new AdminUserDetails(a.getId(), operatorUserId, a.getLarkEmail(),
                a.getPasswordHash(), a.getAccountType(), permissionCodes);
    }

    /**
     * 解析该账号本次登录应装载的权限码（V165 岗位角色）。三条互斥路径：
     * <ol>
     *   <li>{@code SUPER_ADMIN} → 空集。隐式全权由表达式 {@code hasRole('SUPER_ADMIN')} 命中，
     *       不注入全集——新增权限码无需同步，抗遗漏（Story 1.5 既有语义，不变）。</li>
     *   <li>{@code CUSTOM} → 读 {@code admin_account_permissions} 勾选行（Story 1.5 原有形态；
     *       存量 STAFF 账号迁移到 CUSTOM，故行为逐位不变）。</li>
     *   <li>其余岗位角色 → 取 {@link AdminRole#permissionCodes()} 模板，<b>不查表</b>。
     *       所以给某角色新增一个模块权限时，该角色所有账号下次登录即生效，
     *       不需要逐个账号重存权限行——角色定义与实际授权不会漂移。</li>
     * </ol>
     */
    private Set<String> resolvePermissions(AdminAccount a) {
        AdminRole role = a.getRole();
        if (a.getAccountType() == AdminAccountType.SUPER_ADMIN || role == null) {
            return Set.of();
        }
        if (role.isTemplated()) {
            return Set.copyOf(role.permissionCodes());
        }
        return permissions.findByAccountId(a.getId()).stream()
                .map(AdminAccountPermission::getPermissionCode)
                .collect(Collectors.toSet());
    }
}
