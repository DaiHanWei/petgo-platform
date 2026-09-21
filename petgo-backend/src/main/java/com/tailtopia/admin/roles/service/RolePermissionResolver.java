package com.tailtopia.admin.roles.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.roles.domain.AdminRolePermission;
import com.tailtopia.admin.roles.repository.AdminRolePermissionRepository;
import com.tailtopia.admin.roles.repository.AdminRoleRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「一个账号的权限从哪来」的<b>唯一</b>出口（V1.3.0 Story 1.4，AD-4）。登录装载
 * （{@code AdminUserDetailsService}）与账号页回显（{@code AdminAccountService}）都只调这里，消除双真相。
 *
 * <p>四路（AC3）：
 * <ol>
 *   <li>{@code accountType == SUPER_ADMIN} 或 {@code role == null} → 空集（隐式全权，表达式 {@code hasRole} 命中）。</li>
 *   <li>{@code OPS_MANAGER} → 读枚举 {@link AdminRole#permissionCodes()}（保留不迁移，D-13）。</li>
 *   <li>{@code CUSTOM} → 读 {@code admin_account_permissions} 勾选行（现状不动）。</li>
 *   <li>其余（四个已迁移岗位 + Story 1.5 的 {@code ROLE_TEMPLATE}，{@code role_id != null}）→ 读 {@code admin_role_permissions WHERE role_id}。
 *       {@code role_id} 为 NULL 的异常数据 → 空集 + warn（不记邮箱），不抛、不静默给权限。</li>
 * </ol>
 */
@Service
public class RolePermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionResolver.class);

    private final AdminAccountPermissionRepository accountPermissions;
    private final AdminRoleRepository roles;
    private final AdminRolePermissionRepository rolePermissions;

    public RolePermissionResolver(AdminAccountPermissionRepository accountPermissions,
            AdminRoleRepository roles, AdminRolePermissionRepository rolePermissions) {
        this.accountPermissions = accountPermissions;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
    }

    /** 该账号当前生效的权限码（登录装载口径）。 */
    @Transactional(readOnly = true)
    public Set<String> resolve(AdminAccount a) {
        AdminRole role = a.getRole();
        if (a.getAccountType() == AdminAccountType.SUPER_ADMIN || role == null) {
            return Set.of();
        }
        if (role == AdminRole.OPS_MANAGER) {
            return Set.copyOf(role.permissionCodes());
        }
        if (role == AdminRole.CUSTOM) {
            return accountPermissions.findByAccountId(a.getId()).stream()
                    .map(AdminAccountPermission::getPermissionCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (a.getRoleId() == null) {
            log.warn("admin account id={} has table-backed role {} but null role_id; granting no permissions",
                    a.getId(), role);
            return Set.of();
        }
        return codesOfRoleId(a.getRoleId());
    }

    /** 某角色表行的权限码集合（顺序稳定）。 */
    @Transactional(readOnly = true)
    public Set<String> codesOfRoleId(long roleId) {
        return rolePermissions.findByRoleId(roleId).stream()
                .map(AdminRolePermission::getPermissionCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 模板角色的权限码（建号审计摘要、切换角色时的 carry 起点）：OPS_MANAGER 读枚举；
     * 四个已迁移岗位读表（按 code = 枚举名）；SUPER_ADMIN / CUSTOM 为空。
     */
    @Transactional(readOnly = true)
    public List<String> codesOf(AdminRole role) {
        if (role == null || role == AdminRole.SUPER_ADMIN || role == AdminRole.CUSTOM) {
            return List.of();
        }
        if (role == AdminRole.OPS_MANAGER) {
            return role.permissionCodes();
        }
        return roleIdFor(role).map(id -> List.copyOf(codesOfRoleId(id))).orElse(List.of());
    }

    /** 已迁移岗位对应的 {@code admin_roles.id}（SYSTEM 行，code = 枚举名）；非表驱动角色返回 empty。 */
    @Transactional(readOnly = true)
    public Optional<Long> roleIdFor(AdminRole role) {
        if (role == null || !role.isTableBacked() || role.isCustomRoleRef()) {
            return Optional.empty(); // ROLE_TEMPLATE 的 role_id 由赋值入口显式给（1-6），不按 code 查
        }
        return roles.findByCode(role.name()).map(r -> r.getId());
    }
}
