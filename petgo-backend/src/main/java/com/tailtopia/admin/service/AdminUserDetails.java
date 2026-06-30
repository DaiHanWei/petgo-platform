package com.tailtopia.admin.service;

import com.tailtopia.admin.account.domain.AdminAccountType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 后台登录主体（Story 1.1 重构）。承载两类 id：
 * <ul>
 *   <li>{@code adminAccountId}：{@code admin_accounts.id}，后台身份/权限根（认证源真相，与 App 隔离）。</li>
 *   <li>{@code operatorUserId}：合法 {@code users.id}（官方内容作者 shim），供既有后台写入路径取 author/operator——
 *       种子发帖 {@code content_posts.author_id}（有 FK→users）必须用它；举报处理 {@code handled_by}（无 FK）亦用它。
 *       可空（STAFF 无对应 users 行时为 null）。</li>
 * </ul>
 *
 * <p>authorities：{@code ROLE_ADMIN}（保持既有 {@code /admin/**} 门控），超管额外 {@code ROLE_SUPER_ADMIN}。
 * 细粒度 {@code permission_code} authority 与 SUPER_ADMIN 隐式全权由 Story 1.5 引入。密码哈希 BCrypt，绝不外泄。
 */
public class AdminUserDetails implements UserDetails {

    private final long adminAccountId;
    private final Long operatorUserId;
    private final String email;
    private final String passwordHash;
    private final AdminAccountType accountType;
    /** STAFF 的模块权限码（Story 1.5，装载为 authority）；SUPER_ADMIN 隐式全权、此处为空集。 */
    private final Set<String> permissionCodes;

    /** 兼容旧调用（无细粒度权限，permission 空集）：Story 1.5 前的构造形态。 */
    public AdminUserDetails(long adminAccountId, Long operatorUserId, String email,
            String passwordHash, AdminAccountType accountType) {
        this(adminAccountId, operatorUserId, email, passwordHash, accountType, Set.of());
    }

    /** Story 1.5：携带 STAFF 模块权限码（注入为 {@code hasAuthority('<code>')} 可命中的 authority）。 */
    public AdminUserDetails(long adminAccountId, Long operatorUserId, String email,
            String passwordHash, AdminAccountType accountType, Set<String> permissionCodes) {
        this.adminAccountId = adminAccountId;
        this.operatorUserId = operatorUserId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.accountType = accountType;
        this.permissionCodes = permissionCodes == null ? Set.of() : Set.copyOf(permissionCodes);
    }

    public long getAdminAccountId() {
        return adminAccountId;
    }

    public AdminAccountType getAccountType() {
        return accountType;
    }

    /**
     * 官方内容作者 / 操作人 {@code users.id}（既有后台写入路径用，见类注释）。
     * 调用方（如种子发帖）要求非空；STAFF 等无对应 users 行时为 null，调用方需自行保证语义。
     */
    public long getUserId() {
        if (operatorUserId == null) {
            throw new IllegalStateException("当前后台账号无关联的 users.id（不可执行需内容作者的操作）");
        }
        return operatorUserId;
    }

    /** 是否存在可用的 operator/author users.id（不抛异常的探测）。 */
    public boolean hasOperatorUserId() {
        return operatorUserId != null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if (accountType == AdminAccountType.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        }
        // STAFF 细粒度模块权限（Story 1.5）；SUPER_ADMIN 隐式全权经表达式 hasRole('SUPER_ADMIN') 命中，不依赖此。
        for (String code : permissionCodes) {
            authorities.add(new SimpleGrantedAuthority(code));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
