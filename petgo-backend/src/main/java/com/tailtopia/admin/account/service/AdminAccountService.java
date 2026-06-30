package com.tailtopia.admin.account.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.dto.AdminAccountView;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台账号管理（Story 1.5，AC3~AC7）。两级权限授权根：创建 STAFF/超管、分配/调整模块权限、停用/重新激活——
 * **每个写操作经 {@link AdminAuditService} 同事务写审计**（哈希链）。
 *
 * <p>护栏：① 账号**永不硬删**（无 delete 方法）② 超管上限 5（ACTIVE 口径，AC4）③ 停用即时撤权靠
 * {@code AdminSessionGuardFilter}（本服务只置 DISABLED，不造新机制，A1）④ 不删最后一个在职超管（防找回死锁，A3）
 * ⑤ 权限码须属附录 B（{@link AdminPermissions}）。
 */
@Service
public class AdminAccountService {

    /** 超管上限（AC4）。 */
    static final int SUPER_ADMIN_CAP = 5;

    private final AdminAccountRepository accounts;
    private final AdminAccountPermissionRepository permissions;
    private final AdminAuditService auditService;

    public AdminAccountService(AdminAccountRepository accounts,
            AdminAccountPermissionRepository permissions, AdminAuditService auditService) {
        this.accounts = accounts;
        this.permissions = permissions;
        this.auditService = auditService;
    }

    /** 账号列表（含权限摘要），按 id 升序。 */
    @Transactional(readOnly = true)
    public List<AdminAccountView> list() {
        List<AdminAccountView> views = new ArrayList<>();
        for (AdminAccount a : accounts.findAll()) {
            List<String> codes = a.getAccountType() == AdminAccountType.SUPER_ADMIN
                    ? List.of()
                    : permissions.findByAccountId(a.getId()).stream()
                            .map(AdminAccountPermission::getPermissionCode).sorted().toList();
            views.add(new AdminAccountView(a.getId(), a.getLarkEmail(), a.getDisplayName(),
                    a.getAccountType(), a.getStatus(), codes));
        }
        views.sort((x, y) -> Long.compare(x.id(), y.id()));
        return views;
    }

    /**
     * 创建后台账号（AC3/AC4）。STAFF 写其模块权限；SUPER_ADMIN 隐式全权、忽略勾选权限并校验上限。
     *
     * @return 新账号 id
     */
    @Transactional
    public long createAccount(String larkEmail, String displayName, AdminAccountType accountType,
            List<String> permissionCodes, long actorAccountId) {
        String email = larkEmail == null ? "" : larkEmail.trim();
        if (email.isEmpty()) {
            throw AppException.validation("Lark 邮箱不能为空");
        }
        if (displayName == null || displayName.isBlank()) {
            throw AppException.validation("显示名不能为空");
        }
        if (accounts.findByLarkEmail(email).isPresent()) {
            throw AppException.conflict("该 Lark 邮箱已存在后台账号：" + email);
        }
        if (accountType == AdminAccountType.SUPER_ADMIN) {
            assertSuperAdminCap();
        }
        Set<String> codes = sanitizePermissions(accountType, permissionCodes);

        AdminAccount saved = accounts.save(
                AdminAccount.create(email, displayName.trim(), accountType, actorAccountId));
        if (!codes.isEmpty()) {
            permissions.saveAll(codes.stream()
                    .map(c -> new AdminAccountPermission(saved.getId(), c)).toList());
        }

        auditService.record(actorAccountId, AuditActions.ACCOUNT_CREATED, "ADMIN_ACCOUNT",
                String.valueOf(saved.getId()),
                "创建后台账号 " + email + "（" + accountType + "）权限=" + new TreeSet<>(codes));
        return saved.getId();
    }

    /** 调整 STAFF 模块权限（AC7）：diff 增删 + 分别审计。SUPER_ADMIN 不可改权限（隐式全权）。 */
    @Transactional
    public void updatePermissions(long accountId, List<String> permissionCodes, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在"));
        if (a.getAccountType() == AdminAccountType.SUPER_ADMIN) {
            throw AppException.validation("超级管理员隐式全权，无需也不可单独分配模块权限");
        }
        Set<String> desired = sanitizePermissions(AdminAccountType.STAFF, permissionCodes);
        Set<String> current = permissions.findByAccountId(accountId).stream()
                .map(AdminAccountPermission::getPermissionCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> added = new TreeSet<>(desired);
        added.removeAll(current);
        Set<String> removed = new TreeSet<>(current);
        removed.removeAll(desired);
        if (added.isEmpty() && removed.isEmpty()) {
            return; // 无变化
        }

        permissions.deleteByAccountId(accountId);
        if (!desired.isEmpty()) {
            permissions.saveAll(desired.stream()
                    .map(c -> new AdminAccountPermission(accountId, c)).toList());
        }
        if (!added.isEmpty()) {
            auditService.record(actorAccountId, AuditActions.PERMISSION_GRANTED, "ADMIN_ACCOUNT",
                    String.valueOf(accountId), "授予权限 " + added + " 给 " + a.getLarkEmail());
        }
        if (!removed.isEmpty()) {
            auditService.record(actorAccountId, AuditActions.PERMISSION_REVOKED, "ADMIN_ACCOUNT",
                    String.valueOf(accountId), "撤销权限 " + removed + " 自 " + a.getLarkEmail());
        }
    }

    /** 停用账号（AC5，A1 即时撤权靠会话守卫）。 */
    @Transactional
    public void deactivate(long accountId, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在"));
        if (a.getStatus() == AdminAccountStatus.DISABLED) {
            return; // 幂等
        }
        // A3：不停用最后一个在职超管（防找回死锁）。
        if (a.getAccountType() == AdminAccountType.SUPER_ADMIN
                && accounts.countByAccountTypeAndStatus(
                        AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE) <= 1) {
            throw AppException.validation("不能停用最后一个在职超级管理员");
        }
        a.setStatus(AdminAccountStatus.DISABLED);
        accounts.save(a);
        auditService.record(actorAccountId, AuditActions.ACCOUNT_DEACTIVATED, "ADMIN_ACCOUNT",
                String.valueOf(accountId), "停用后台账号 " + a.getLarkEmail());
    }

    /** 重新激活账号（AC5）。 */
    @Transactional
    public void reactivate(long accountId, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在"));
        if (a.getStatus() == AdminAccountStatus.ACTIVE) {
            return; // 幂等
        }
        // 重新激活超管会回填名额，需复查上限。
        if (a.getAccountType() == AdminAccountType.SUPER_ADMIN) {
            assertSuperAdminCap();
        }
        a.setStatus(AdminAccountStatus.ACTIVE);
        accounts.save(a);
        auditService.record(actorAccountId, AuditActions.ACCOUNT_REACTIVATED, "ADMIN_ACCOUNT",
                String.valueOf(accountId), "重新激活后台账号 " + a.getLarkEmail());
    }

    /** 超管上限校验（AC4）：ACTIVE 的 SUPER_ADMIN 须 < 5。 */
    void assertSuperAdminCap() {
        long active = accounts.countByAccountTypeAndStatus(
                AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE);
        if (active >= SUPER_ADMIN_CAP) {
            throw AppException.validation("超级管理员已达上限 " + SUPER_ADMIN_CAP + " 个，无法再创建/启用");
        }
    }

    /** 校验并归一权限码：SUPER_ADMIN 忽略（隐式全权）；STAFF 须全部属附录 B。 */
    private Set<String> sanitizePermissions(AdminAccountType type, List<String> codes) {
        if (type == AdminAccountType.SUPER_ADMIN || codes == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String c : codes) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String code = c.trim();
            if (!AdminPermissions.isValid(code)) {
                throw AppException.validation("非法权限码：" + code);
            }
            result.add(code);
        }
        return result;
    }
}
