package com.tailtopia.admin.roles.service;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.roles.domain.AdminRoleEntity;
import com.tailtopia.admin.roles.domain.AdminRolePermission;
import com.tailtopia.admin.roles.dto.AdminRoleView;
import com.tailtopia.admin.roles.dto.PermissionMatrixView;
import com.tailtopia.admin.roles.dto.RoleChange;
import com.tailtopia.admin.roles.dto.RoleOption;
import com.tailtopia.admin.roles.dto.RoleSelection;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.roles.domain.RoleType;
import java.util.function.Function;
import com.tailtopia.admin.roles.repository.AdminRolePermissionRepository;
import com.tailtopia.admin.roles.repository.AdminRoleRepository;
import com.tailtopia.admin.shared.AdminPageCatalog;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色配置（V1.3.0 Story 1.5，AB-21A）。列表 / 矩阵 / 新建 / 改权限 / 改名 / 删除，每个写操作同事务写审计。
 *
 * <p>护栏：① SYSTEM 预置角色只能改权限，名称与 code 不可改、不可删（{@code admin.err.role.systemImmutable}）；
 * ② 提交的码必须全部 ∈ {@link AdminPermissions#ALL}；③ 有账号引用的角色不能删（服务层先查，DB RESTRICT 兜底）；
 * ④ 权限真变时该角色下<b>全部账号</b> {@code security_version} +1（一条 UPDATE），重新登录后生效（D-2）——
 * 横幅只能说「重新登录后生效」，不是「立即生效」。
 */
@Service
public class AdminRoleService {

    /** 审计 summary 列 varchar(500)。 */
    static final int SUMMARY_MAX = 500;

    private final AdminRoleRepository roles;
    private final AdminRolePermissionRepository rolePermissions;
    private final AdminAccountRepository accounts;
    private final AdminAuditService auditService;

    public AdminRoleService(AdminRoleRepository roles, AdminRolePermissionRepository rolePermissions,
            AdminAccountRepository accounts, AdminAuditService auditService) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.accounts = accounts;
        this.auditService = auditService;
    }

    /**
     * 账号页角色下拉（Story 1.6 AC1）：超管、运营主管（枚举）→ 预置四岗（{@code enum:<CODE>}，存量账号默认选中不用查表）
     * → 自定义若干（{@code tpl:<id>}）→ 自定义勾选（CUSTOM）。
     *
     * @param label i18n 取文案（通常 {@code msg::get}）
     */
    @Transactional(readOnly = true)
    public List<RoleOption> options(Function<String, String> label) {
        List<RoleOption> out = new ArrayList<>();
        for (AdminRole r : List.of(AdminRole.SUPER_ADMIN, AdminRole.OPS_MANAGER)) {
            out.add(new RoleOption("enum:" + r.name(), label.apply(r.titleCode()), r.descriptionCode(),
                    false, false, null, r));
        }
        List<AdminRoleEntity> rows = roles.findAllByOrderByRoleTypeAscIdAsc();
        for (AdminRoleEntity row : rows) {
            if (row.getRoleType() == RoleType.SYSTEM) {
                AdminRole r = AdminRole.valueOf(row.getCode());
                out.add(new RoleOption("enum:" + r.name(), label.apply(row.getNameKey()), r.descriptionCode(),
                        true, false, row.getId(), r));
            }
        }
        for (AdminRoleEntity row : rows) {
            if (row.getRoleType() == RoleType.CUSTOM) {
                out.add(new RoleOption("tpl:" + row.getId(), row.getName(), null,
                        true, true, row.getId(), AdminRole.ROLE_TEMPLATE));
            }
        }
        out.add(new RoleOption("enum:CUSTOM", label.apply(AdminRole.CUSTOM.titleCode()),
                AdminRole.CUSTOM.descriptionCode(), false, false, null, AdminRole.CUSTOM));
        return out;
    }

    /**
     * 把下拉选项值解析成 {@link RoleSelection}（Story 1.6 AC2）：{@code enum:<NAME>}（ROLE_TEMPLATE 不可直选）
     * 预置四岗补上表 id；{@code tpl:<id>} 按表行类型：SYSTEM → 对应枚举 + id，CUSTOM → ROLE_TEMPLATE + id。
     */
    @Transactional(readOnly = true)
    public RoleSelection resolveSelection(String value) {
        String v = value == null ? "" : value.trim();
        try {
            if (v.startsWith("enum:")) {
                AdminRole r = AdminRole.valueOf(v.substring(5));
                if (r == AdminRole.ROLE_TEMPLATE) {
                    throw AppException.validation("必须选择岗位角色").code("admin.err.account.roleRequired");
                }
                Long roleId = null;
                if (r.isTableBacked()) {
                    roleId = roles.findByCode(r.name()).map(AdminRoleEntity::getId)
                            .orElseThrow(() -> AppException.notFound("角色不存在").code("admin.err.role.notFound"));
                }
                return RoleSelection.ofEnum(r, roleId);
            }
            if (v.startsWith("tpl:")) {
                AdminRoleEntity row = get(Long.parseLong(v.substring(4)));
                if (row.getRoleType() == RoleType.SYSTEM) {
                    return RoleSelection.ofEnum(AdminRole.valueOf(row.getCode()), row.getId());
                }
                return new RoleSelection(AdminRole.ROLE_TEMPLATE, row.getId(),
                        row.getName() + "(" + row.getCode() + ")");
            }
        } catch (IllegalArgumentException e) {
            // 非法枚举名 / 非数字 id → 下面统一按「未选角色」报
        }
        throw AppException.validation("必须选择岗位角色").code("admin.err.account.roleRequired");
    }

    /** 角色列表（预置在前）：权限数 + 使用中账号数。 */
    @Transactional(readOnly = true)
    public List<AdminRoleView> list() {
        List<AdminRoleView> out = new ArrayList<>();
        for (AdminRoleEntity r : roles.findAllByOrderByRoleTypeAscIdAsc()) {
            out.add(new AdminRoleView(r.getId(), r.getCode(), r.getName(), r.getNameKey(), r.getRoleType(),
                    rolePermissions.findByRoleId(r.getId()).size(), accounts.countByRoleId(r.getId())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AdminRoleEntity get(long id) {
        return roles.findById(id)
                .orElseThrow(() -> AppException.notFound("角色不存在").code("admin.err.role.notFound"));
    }

    @Transactional(readOnly = true)
    public long accountCount(long roleId) {
        return accounts.countByRoleId(roleId);
    }

    /** 权限矩阵：{@code roleId} 为 null 表示新建（全部未勾）。 */
    @Transactional(readOnly = true)
    public PermissionMatrixView matrix(Long roleId) {
        Set<String> checked = roleId == null ? Set.of() : codesOf(roleId);
        List<PermissionMatrixView.GroupRows> groups = AdminPageCatalog.byGroup().entrySet().stream()
                .map(e -> new PermissionMatrixView.GroupRows(e.getKey(), e.getValue()))
                .toList();
        return new PermissionMatrixView(groups, checked);
    }

    private Set<String> codesOf(long roleId) {
        Set<String> s = new LinkedHashSet<>();
        rolePermissions.findByRoleId(roleId).forEach(p -> s.add(p.getPermissionCode()));
        return s;
    }

    /**
     * 新建自定义角色：name trim 非空 ≤60、忽略大小写唯一；至少 1 项权限；{@code code} 先以临时值插入拿到 id
     * 再改为 {@code role-<id>}（同事务，不依赖序列名）。
     *
     * @return 新角色 id
     */
    @Transactional
    public long create(String name, List<String> permissionCodes, long actorAccountId) {
        String n = normalizeName(name);
        Set<String> codes = sanitize(permissionCodes);
        if (codes.isEmpty()) {
            throw AppException.validation("至少勾选 1 项权限").code("admin.err.role.noPermission");
        }
        assertNameFree(n, null);
        AdminRoleEntity saved = roles.save(AdminRoleEntity.newCustom("tmp-" + UUID.randomUUID(), n, actorAccountId));
        saved.setCode("role-" + saved.getId());
        saved = roles.save(saved);
        long id = saved.getId();
        rolePermissions.saveAll(codes.stream().map(c -> new AdminRolePermission(id, c)).toList());
        auditService.record(actorAccountId, AuditActions.ROLE_CREATED, "ADMIN_ROLE", String.valueOf(id),
                truncate("新建自定义角色 " + n + "（" + saved.getCode() + "）权限 " + codes.size() + " 项：" + new TreeSet<>(codes)));
        return id;
    }

    /**
     * 改权限（预置 / 自定义通用）：按 diff 增删 → 该角色下全部账号 bump → 审计。无变化 no-op（不 bump、不审计）。
     */
    @Transactional
    public RoleChange updatePermissions(long roleId, List<String> permissionCodes, long actorAccountId) {
        AdminRoleEntity r = get(roleId);
        Set<String> desired = sanitize(permissionCodes);
        if (desired.isEmpty()) {
            throw AppException.validation("至少勾选 1 项权限").code("admin.err.role.noPermission");
        }
        Set<String> current = codesOf(roleId);
        Set<String> added = new TreeSet<>(desired);
        added.removeAll(current);
        Set<String> removed = new TreeSet<>(current);
        removed.removeAll(desired);
        long affected = accounts.countByRoleId(roleId);
        if (added.isEmpty() && removed.isEmpty()) {
            return new RoleChange(roleId, r.getCode(), 0, 0, affected);
        }
        rolePermissions.deleteByRoleId(roleId);
        rolePermissions.flush();
        rolePermissions.saveAll(desired.stream().map(c -> new AdminRolePermission(roleId, c)).toList());
        // AD-1：该角色下全部账号下一次请求被踢重登，重登后权限即新值（D-2 重登生效）。
        accounts.bumpSecurityVersionByRoleId(roleId);
        auditService.record(actorAccountId, AuditActions.ROLE_UPDATED, "ADMIN_ROLE", String.valueOf(roleId),
                truncate("角色 " + r.getCode() + " 权限 +" + added.size() + " / −" + removed.size()
                        + "（影响 " + affected + " 个账号）新增 " + added + " 移除 " + removed));
        return new RoleChange(roleId, r.getCode(), added.size(), removed.size(), affected);
    }

    /** 改名：仅 CUSTOM；同名幂等。 */
    @Transactional
    public void rename(long roleId, String name, long actorAccountId) {
        AdminRoleEntity r = get(roleId);
        assertCustom(r);
        renameInternal(r, name, actorAccountId);
    }

    /**
     * 编辑自定义角色 = 改名 + 改权限<b>同一事务</b>（任一校验失败整体回滚，不会出现「名改了、权限没保存」）。
     * {@code name} 为 null 表示不改名。预置角色不经此方法（走 {@link #updatePermissions}）。
     */
    @Transactional
    public RoleChange updateCustom(long roleId, String name, List<String> permissionCodes, long actorAccountId) {
        AdminRoleEntity r = get(roleId);
        assertCustom(r);
        if (name != null) {
            renameInternal(r, name, actorAccountId);
        }
        return updatePermissions(roleId, permissionCodes, actorAccountId);
    }

    private void renameInternal(AdminRoleEntity r, String name, long actorAccountId) {
        String n = normalizeName(name);
        if (n.equals(r.getName())) {
            return;
        }
        assertNameFree(n, r.getId());
        String old = r.getName();
        r.setName(n);
        roles.save(r);
        auditService.record(actorAccountId, AuditActions.ROLE_UPDATED, "ADMIN_ROLE", String.valueOf(r.getId()),
                truncate("角色 " + r.getCode() + " 改名 " + old + " → " + n));
    }

    /** 删除：仅 CUSTOM；有账号引用则拒（带账号数）；权限行 CASCADE。 */
    @Transactional
    public void delete(long roleId, long actorAccountId) {
        AdminRoleEntity r = get(roleId);
        assertCustom(r);
        long inUse = accounts.countByRoleId(roleId);
        if (inUse > 0) {
            throw AppException.validation("该角色仍有 " + inUse + " 个账号在用，不能删除")
                    .code("admin.err.role.inUse", inUse);
        }
        rolePermissions.deleteByRoleId(roleId);
        roles.delete(r);
        auditService.record(actorAccountId, AuditActions.ROLE_DELETED, "ADMIN_ROLE", String.valueOf(roleId),
                truncate("删除自定义角色 " + r.getName() + "（" + r.getCode() + "）"));
    }

    private void assertCustom(AdminRoleEntity r) {
        if (r.isSystem()) {
            throw AppException.validation("系统预置角色的名称与存在不可改，只能编辑权限")
                    .code("admin.err.role.systemImmutable");
        }
    }

    private void assertNameFree(String name, Long selfId) {
        for (AdminRoleEntity other : roles.findAll()) {
            if (selfId != null && other.getId().equals(selfId)) {
                continue;
            }
            if (other.getName() != null && other.getName().equalsIgnoreCase(name)) {
                throw AppException.conflict("已存在同名角色：" + name).code("admin.err.role.nameExists", name);
            }
        }
    }

    private static String normalizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw AppException.validation("角色名称不能为空").code("admin.err.role.nameRequired");
        }
        if (n.length() > 60) {
            throw AppException.validation("角色名称不能超过 60 个字符").code("admin.err.role.nameTooLong");
        }
        return n;
    }

    /** 校验并归一权限码：全部须属附录 B（{@link AdminPermissions#ALL}）。 */
    static Set<String> sanitize(List<String> codes) {
        Set<String> result = new LinkedHashSet<>();
        if (codes == null) {
            return result;
        }
        for (String c : codes) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String code = c.trim();
            if (!AdminPermissions.isValid(code)) {
                throw AppException.validation("非法权限码：" + code).code("admin.err.account.badPermissionCode", code);
            }
            result.add(code);
        }
        return result;
    }

    /** 审计 summary ≤500（列宽），超长截断并标记。 */
    static String truncate(String s) {
        return s.length() <= SUMMARY_MAX ? s : s.substring(0, SUMMARY_MAX - 1) + "…";
    }
}
