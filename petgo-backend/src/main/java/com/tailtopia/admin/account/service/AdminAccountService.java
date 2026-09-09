package com.tailtopia.admin.account.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.dto.AdminAccountView;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAlertService;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.roles.service.RolePermissionResolver;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
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

    /** 邮箱格式（与建号表单 {@code @Email} 同口径的宽松校验：本地部分@域名，无空白、恰一个 @；不强制顶级域）。 */
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+$");

    private final AdminAccountRepository accounts;
    private final AdminAccountPermissionRepository permissions;
    private final AdminAuditService auditService;
    private final AdminAlertService alertService;
    /** 权限来源唯一出口（V1.3.0 Story 1.4）：列表回显 / carry / 审计摘要都经它，与登录装载同口径。 */
    private final RolePermissionResolver resolver;
    /** bootstrap 超管邮箱（与 {@code AdminBootstrap} 同一 env）；空表示未配置、护栏不生效。 */
    private final String bootstrapEmail;

    public AdminAccountService(AdminAccountRepository accounts,
            AdminAccountPermissionRepository permissions, AdminAuditService auditService,
            AdminAlertService alertService, RolePermissionResolver resolver,
            @Value("${ADMIN_BOOTSTRAP_EMAIL:}") String bootstrapEmail) {
        this.accounts = accounts;
        this.permissions = permissions;
        this.auditService = auditService;
        this.alertService = alertService;
        this.resolver = resolver;
        this.bootstrapEmail = bootstrapEmail == null ? "" : bootstrapEmail.trim();
    }

    /**
     * 账号列表（含<b>生效</b>权限摘要），按 id 升序。
     *
     * <p>「生效」指与登录时装载的权限一致（{@code AdminUserDetailsService.resolvePermissions} 同口径）：
     * 超管为空（隐式全权）、模板角色取角色定义、{@code CUSTOM} 取勾选行。
     * 页面因此不会出现「勾选框显示有权限、实际登录却没有」的假象。
     */
    @Transactional(readOnly = true)
    public List<AdminAccountView> list() {
        List<AdminAccountView> views = new ArrayList<>();
        for (AdminAccount a : accounts.findAll()) {
            views.add(new AdminAccountView(a.getId(), a.getLarkEmail(), a.getDisplayName(),
                    a.getAccountType(), a.getRole(), a.getStatus(), effectivePermissions(a)));
        }
        views.sort((x, y) -> Long.compare(x.id(), y.id()));
        return views;
    }

    /** 该账号实际生效的权限码（排序稳定，供 UI 回显）——与登录装载同源（{@link RolePermissionResolver}）。 */
    private List<String> effectivePermissions(AdminAccount a) {
        return resolver.resolve(a).stream().sorted().toList();
    }

    /**
     * 创建后台账号（AC3/AC4；V165 改为<b>按岗位角色</b>建号）。
     *
     * <p>{@code SUPER_ADMIN} 隐式全权并校验上限 5；模板角色（运营/发货/客服/…）的权限由
     * {@link AdminRole#permissionCodes()} 决定，<b>忽略传入的勾选</b>——避免建号时勾了一份、
     * 登录时按角色装另一份的两套真相。仅 {@code CUSTOM} 落 {@code admin_account_permissions} 勾选行。
     *
     * @return 新账号 id
     */
    @Transactional
    public long createAccount(String larkEmail, String displayName, AdminRole role,
            List<String> permissionCodes, long actorAccountId) {
        String email = larkEmail == null ? "" : larkEmail.trim();
        if (email.isEmpty()) {
            throw AppException.validation("Lark 邮箱不能为空").code("admin.err.account.emailRequired");
        }
        if (displayName == null || displayName.isBlank()) {
            throw AppException.validation("显示名不能为空").code("admin.err.account.displayNameRequired");
        }
        if (role == null || role == AdminRole.ROLE_TEMPLATE) {
            throw AppException.validation("必须选择岗位角色").code("admin.err.account.roleRequired");
        }
        // V1.3.0 Story 1.3（D-21）：只对 ACTIVE 账号查重，已停用账号的邮箱视为已释放（部分唯一索引兜底）。
        if (accounts.findByLarkEmailIgnoreCaseAndStatus(email, AdminAccountStatus.ACTIVE).isPresent()) {
            throw AppException.conflict("该 Lark 邮箱已存在后台账号：" + email)
                    .code("admin.err.account.emailExists", email);
        }
        if (role.isSuperAdmin()) {
            assertSuperAdminCap();
        }
        // 仅 CUSTOM 需要勾选权限；模板角色与超管的权限不落表（登录时按角色解析）。
        Set<String> codes = role.isTemplated()
                ? Set.of()
                : sanitizePermissions(AdminAccountType.STAFF, permissionCodes);

        AdminAccount fresh = AdminAccount.create(email, displayName.trim(), role, actorAccountId);
        // Story 1.4：四个已迁移岗位落 role_id（权限按表解析）；SUPER_ADMIN / OPS_MANAGER / CUSTOM 保持 NULL。
        fresh.setRoleId(resolver.roleIdFor(role).orElse(null));
        AdminAccount saved = accounts.save(fresh);
        if (!codes.isEmpty()) {
            permissions.saveAll(codes.stream()
                    .map(c -> new AdminAccountPermission(saved.getId(), c)).toList());
        }

        // 模板角色只记角色名 + 权限条数：整份码表能由 AdminRole 反查，且随 git 留痕；
        // 摘要列只有 varchar(500)，运营主管那 40 多个码直接撑爆它，而审计写失败会回滚整个建号事务。
        String permSummary = role.isTemplated()
                ? resolver.codesOf(role).size() + " 项（按角色）"
                : String.valueOf(new TreeSet<>(codes));
        auditService.record(actorAccountId, AuditActions.ACCOUNT_CREATED, "ADMIN_ACCOUNT",
                String.valueOf(saved.getId()),
                "创建后台账号 " + email + "（角色 " + role + "）权限=" + permSummary);
        return saved.getId();
    }

    /**
     * 改岗位角色（V165）。切换语义刻意做成不丢权限、不留残影：
     * <ul>
     *   <li>切到 {@code CUSTOM}：把<b>当前生效</b>的权限码固化成勾选行，作为手工微调的起点
     *       —— 「基于运营岗再减两个权限」这种最常见的诉求不用从零勾。</li>
     *   <li>切到模板角色：删掉勾选行（此后权限由角色定义），避免库里留着一份已经不生效的权限、
     *       日后误读为「他还有这些权限」。</li>
     *   <li>切到/离开 {@code SUPER_ADMIN}：分别校验上限 5 与「不降级最后一个在职超管」（A3 同源护栏，
     *       否则降级等于变相停用最后一个超管，一样会把自己锁在门外）。</li>
     * </ul>
     * 权限变更下次登录生效（与 {@link #updatePermissions} 一致；即时撤权仍只由停用 + 会话守卫负责）。
     */
    @Transactional
    public void changeRole(long accountId, AdminRole newRole, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在").code("admin.err.account.notFound"));
        if (newRole == null || newRole == AdminRole.ROLE_TEMPLATE) {
            // ROLE_TEMPLATE 必须连同 role_id 一起赋值（Story 1.6 的自定义角色入口），不走本方法。
            throw AppException.validation("必须选择岗位角色").code("admin.err.account.roleRequired");
        }
        // V1.3.0 Story 1.2 self 护栏：放在幂等判断之前——「给自己选当前角色再保存」也应被拒。
        if (accountId == actorAccountId) {
            throw AppException.validation("不能修改自己的岗位角色").code("admin.err.account.selfRoleChange");
        }
        AdminRole oldRole = a.getRole();
        if (oldRole == newRole) {
            return; // 幂等
        }
        if (newRole.isSuperAdmin()) {
            assertSuperAdminCap();
        } else if (oldRole != null && oldRole.isSuperAdmin()
                && a.getStatus() == AdminAccountStatus.ACTIVE
                && accounts.countByAccountTypeAndStatus(
                        AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE) <= 1) {
            throw AppException.validation("不能降级最后一个在职超级管理员").code("admin.err.account.lastSuperAdminDemote");
        }

        List<String> carried = effectivePermissions(a);
        a.setRole(newRole);
        a.setRoleId(resolver.roleIdFor(newRole).orElse(null)); // Story 1.4：表驱动岗位设 role_id，其余清空
        a.bumpSecurityVersion(); // AD-1：角色真变 → 会话下次请求被踢重登
        accounts.save(a);

        permissions.deleteByAccountId(accountId);
        if (!newRole.isTemplated() && !carried.isEmpty()) {
            permissions.saveAll(carried.stream()
                    .map(c -> new AdminAccountPermission(accountId, c)).toList());
        }

        auditService.record(actorAccountId, AuditActions.ACCOUNT_ROLE_CHANGED, "ADMIN_ACCOUNT",
                String.valueOf(accountId),
                "岗位角色 " + oldRole + " → " + newRole + "（" + a.getLarkEmail() + "）");
    }

    /** 调整 STAFF 模块权限（AC7）：diff 增删 + 分别审计。SUPER_ADMIN 不可改权限（隐式全权）。 */
    @Transactional
    public void updatePermissions(long accountId, List<String> permissionCodes, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在").code("admin.err.account.notFound"));
        if (a.getAccountType() == AdminAccountType.SUPER_ADMIN) {
            throw AppException.validation("超级管理员隐式全权，无需也不可单独分配模块权限").code("admin.err.account.superAdminImplicit");
        }
        // V165：模板角色的权限由角色定义唯一决定。允许在这里逐码改会立刻造出第二份真相——
        // 表里存一套、登录时按角色装另一套。要微调请先把角色切成「自定义」（切换会带上当前权限）。
        if (a.getRole() != null && a.getRole().isTemplated()) {
            // 文案里不带角色名：本地化后的角色名要再解析一层 key，而账号页那一行本来就显示着角色，
            // 报错里重复一遍没有增量信息，却会引入「参数是码还是文案」的歧义。
            throw AppException.validation(
                    "该账号按岗位角色「" + a.getRole() + "」授权，不能逐条改权限；如需微调请先改为「自定义」角色")
                    .code("admin.err.account.templatedRoleNoPerCode");
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
        a.bumpSecurityVersion(); // AD-1：权限真变（added/removed 非空）→ 踢重登
        accounts.save(a);
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
                .orElseThrow(() -> AppException.notFound("后台账号不存在").code("admin.err.account.notFound"));
        // V1.3.0 Story 1.2 self 护栏（服务层是安全边界；模板禁用态只是体验）。
        if (accountId == actorAccountId) {
            throw AppException.validation("不能停用自己的账号").code("admin.err.account.selfDeactivate");
        }
        if (a.getStatus() == AdminAccountStatus.DISABLED) {
            return; // 幂等
        }
        // A3：不停用最后一个在职超管（防找回死锁）。
        if (a.getAccountType() == AdminAccountType.SUPER_ADMIN
                && accounts.countByAccountTypeAndStatus(
                        AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE) <= 1) {
            throw AppException.validation("不能停用最后一个在职超级管理员").code("admin.err.account.lastSuperAdminDisable");
        }
        a.setStatus(AdminAccountStatus.DISABLED);
        a.bumpSecurityVersion(); // AD-1：停用真变 → 版本 +1（会话守卫仍先判 ACTIVE、走 ?expired）
        accounts.save(a);
        auditService.record(actorAccountId, AuditActions.ACCOUNT_DEACTIVATED, "ADMIN_ACCOUNT",
                String.valueOf(accountId), "停用后台账号 " + a.getLarkEmail());
    }

    /**
     * 账号安全版本号 +1（V1.3.0 Story 1.1，AD-1）——公开、独立事务，供后续横切调用：
     * 1.3 换绑邮箱、1.5 角色模板改权限后对该角色下全部账号批量 bump。
     * 版本号是内部机制，不写审计；{@code reactivate} 不调（对方本来就登不进）。
     */
    @Transactional
    public void bumpSecurityVersion(long accountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在").code("admin.err.account.notFound"));
        a.bumpSecurityVersion();
        accounts.save(a);
    }

    /**
     * 改显示名（V1.3.0 Story 1.2，AB-16A ①）。trim 后非空且 ≤100（与列 {@code display_name VARCHAR(100)} 一致）；
     * 与旧值相同幂等 no-op（不审计）。<b>不</b> bump 安全版本号（D-2：显示名不参与鉴权，顶栏名下次登录自然更新）。
     */
    @Transactional
    public void rename(long accountId, String displayName, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在").code("admin.err.account.notFound"));
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            throw AppException.validation("显示名不能为空").code("admin.err.account.displayNameRequired");
        }
        if (name.length() > 100) {
            throw AppException.validation("显示名不能超过 100 个字符").code("admin.err.account.displayNameTooLong");
        }
        String old = a.getDisplayName();
        if (name.equals(old)) {
            return; // 幂等
        }
        a.setDisplayName(name);
        accounts.save(a);
        auditService.record(actorAccountId, AuditActions.ACCOUNT_RENAMED, "ADMIN_ACCOUNT",
                String.valueOf(accountId), "显示名 " + old + " → " + name + "（" + a.getLarkEmail() + "）");
    }

    /**
     * 换绑 Lark 邮箱（V1.3.0 Story 1.3，AB-16A ②，D-21）= 身份移交。改邮箱、bump 安全版本号、写审计
     * 三件事同一事务：任一失败整体回滚，不会出现「邮箱换了但旧人还在线」或「换了没留痕」。
     * 顺序：findById → 须 ACTIVE → bootstrap 邮箱拒绝 → trim + 格式 → 同值 no-op → ACTIVE 唯一性（排除自身）
     * → 写 larkEmail → bump → 审计 → 超管告警（只记事件 + actorId，不记邮箱）。
     */
    @Transactional
    public void rebindEmail(long accountId, String newEmail, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在").code("admin.err.account.notFound"));
        if (a.getStatus() != AdminAccountStatus.ACTIVE) {
            throw AppException.validation("账号已停用，请先重新激活再换绑邮箱").code("admin.err.account.rebindDisabled");
        }
        if (isBootstrapEmail(a.getLarkEmail())) {
            throw AppException.validation("bootstrap 超管的邮箱不能换绑").code("admin.err.account.bootstrapRebind");
        }
        String email = newEmail == null ? "" : newEmail.trim();
        if (email.isEmpty()) {
            throw AppException.validation("Lark 邮箱不能为空").code("admin.err.account.emailRequired");
        }
        if (!EMAIL.matcher(email).matches()) {
            throw AppException.validation("邮箱格式不正确").code("admin.err.account.emailInvalid");
        }
        String old = a.getLarkEmail();
        if (email.equalsIgnoreCase(old)) {
            return; // 幂等
        }
        if (accounts.existsByLarkEmailIgnoreCaseAndStatusAndIdNot(email, AdminAccountStatus.ACTIVE, accountId)) {
            throw AppException.conflict("该 Lark 邮箱已存在后台账号：" + email)
                    .code("admin.err.account.emailExists", email);
        }
        a.setLarkEmail(email);
        a.bumpSecurityVersion(); // AD-1：旧持有人下一次请求被踢到 /admin/login?relogin
        accounts.save(a);
        auditService.record(actorAccountId, AuditActions.ACCOUNT_EMAIL_REBOUND, "ADMIN_ACCOUNT",
                String.valueOf(accountId), "Lark 邮箱 " + old + " → " + email);
        alertService.alertSuperAdmins(AuditActions.ACCOUNT_EMAIL_REBOUND, actorAccountId);
    }

    /** 是否 bootstrap 超管邮箱（忽略大小写、trim；env 未配置时恒 false）。供页面禁用态与服务层护栏共用。 */
    public boolean isBootstrapEmail(String email) {
        return !bootstrapEmail.isEmpty() && email != null
                && bootstrapEmail.toLowerCase(Locale.ROOT).equals(email.trim().toLowerCase(Locale.ROOT));
    }

    /** 重新激活账号（AC5）。 */
    @Transactional
    public void reactivate(long accountId, long actorAccountId) {
        AdminAccount a = accounts.findById(accountId)
                .orElseThrow(() -> AppException.notFound("后台账号不存在").code("admin.err.account.notFound"));
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
            throw AppException.validation("超级管理员已达上限 " + SUPER_ADMIN_CAP + " 个，无法再创建/启用")
                    .code("admin.err.account.superAdminCap", SUPER_ADMIN_CAP);
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
                throw AppException.validation("非法权限码：" + code).code("admin.err.account.badPermissionCode", code);
            }
            result.add(code);
        }
        return result;
    }
}
