package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.admin.usermgmt.domain.DeletionType;
import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.admin.usermgmt.dto.AdminUserRow;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.AuthService;
import com.tailtopia.consult.service.ConsultHistoryService;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台用户只读聚合（Story 3.1，AB-UA-01）。**纯只读**：搜索 + 详情五块。跨模块**一律经各 owning service**
 * （auth/profile/content/consult），禁直读其 repository、禁跨库 join。问诊**仅元数据**，绝不读 IM 正文/AI 上下文/媒体。
 */
@Service
public class AdminUserService {

    private final AccountQueryService accountQuery;
    private final ProfileService profileService;
    private final ContentService contentService;
    private final ConsultHistoryService consultHistory;
    private final AuthService authService;
    private final ConsultInterruptService consultInterrupt;
    private final AdminAuditService auditService;
    private final AccountDeletionService accountDeletionService;
    private final PawCoinWalletService pawCoinWallet;
    /**
     * 仅供手机号筛选与召回名单导出（Story 11.4）。
     *
     * <p>⚠️ 其余读取一律走 {@code accountQuery} —— 本类不直接查 users 表是既有约定；
     * 这里破例是因为「按 phone 是否为空筛选 + 分页」必须写在 SQL 的 WHERE 里
     * （捞出来再筛会破坏分页），而 AccountQueryService 不该为一个后台专用筛选条件开口。
     */
    private final UserRepository users;

    public AdminUserService(AccountQueryService accountQuery, ProfileService profileService,
            ContentService contentService, ConsultHistoryService consultHistory,
            AuthService authService, ConsultInterruptService consultInterrupt,
            AdminAuditService auditService, AccountDeletionService accountDeletionService,
            PawCoinWalletService pawCoinWallet, UserRepository users) {
        this.accountQuery = accountQuery;
        this.profileService = profileService;
        this.contentService = contentService;
        this.consultHistory = consultHistory;
        this.authService = authService;
        this.consultInterrupt = consultInterrupt;
        this.auditService = auditService;
        this.accountDeletionService = accountDeletionService;
        this.pawCoinWallet = pawCoinWallet;
        this.users = users;
    }

    /**
     * 后台赠送 PawCoin（bug 20260728-389）。经 owning service {@link PawCoinWalletService#credit} 以
     * {@code BONUS} 类型入账（钱包/总账/流水三写原子，计 PLATFORM_REVENUE 科目，对账不破坏）；幂等键取页面
     * 渲染时生成的一次性 token（防双击/刷新重复入账）；同事务写审计 PAWCOIN_GRANTED（含数量/原因）。
     */
    @Transactional
    public void grantPawCoin(long userId, long coins, String reason, String idempotencyToken,
            long actorAccountId) {
        if (coins <= 0) {
            throw AppException.validation("赠送数量必须为正整数").code("admin.err.user.grantAmountPositive");
        }
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("赠送原因不能为空").code("admin.err.user.grantReasonRequired");
        }
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            throw AppException.validation("缺少幂等标识，请刷新页面后重试").code("admin.err.user.missingIdempotencyKey");
        }
        User target = accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        if (target.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，不可赠送").code("admin.err.user.deletedNoGrant");
        }
        String idempotencyKey = "admin-grant:" + idempotencyToken.trim();
        pawCoinWallet.credit(userId, coins, PawCoinTxnType.BONUS, "ADMIN_GRANT", actorAccountId,
                idempotencyKey);
        auditService.record(actorAccountId, AuditActions.PAWCOIN_GRANTED, "USER",
                String.valueOf(userId),
                "赠送 PawCoin（数量：" + coins + "；原因：" + reason.trim() + "；幂等键：" + idempotencyKey + "）");
    }

    /**
     * 删除用户（Story 3.3，AB-UA-03，不可逆）。类型 + 备注必填。先写 USER_DELETED 审计（永久记录，含类型/备注/操作人）；
     * D2(VIOLATION) 前置下架该用户全部内容；最终复用既有 7.3 {@link AccountDeletionService#requestDeletion}
     * 触发级联（用户行物理删 → UGC 经 AuthorView 自动匿名 + 档案/名片删 + 问诊匿名 + 个人图/IM 媒体删）。
     * **不改 7.3 编排/表**；类型分支在本编排层。
     */
    @Transactional
    public void deleteUser(long userId, DeletionType type, String note, long actorAccountId) {
        if (type == null) {
            throw AppException.validation("请选择删除类型（注销 / 违规）").code("admin.err.user.deleteTypeRequired");
        }
        if (note == null || note.isBlank()) {
            throw AppException.validation("删除备注不能为空").code("admin.err.user.deleteNoteRequired");
        }
        User target = accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        // 已注销账号仅展示，禁止重复删除（否则重写审计 + 重触发级联）。
        if (target.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，无需重复删除").code("admin.err.user.alreadyDeleted");
        }

        // 永久记录（append-only）：类型 + 备注 + 操作人；不落 PII。
        auditService.record(actorAccountId, AuditActions.USER_DELETED, "USER", String.valueOf(userId),
                "删除用户（类型：" + type + "；备注：" + note.trim() + "）");

        // D2：先下架全部内容（先下架后注销最稳，避免作者删后漏下架）。
        if (type == DeletionType.VIOLATION) {
            contentService.takedownAllByAuthor(userId);
        }
        // D1/D2 共用：触发既有级联注销（幂等 + 状态机 + 失败重扫）。
        accountDeletionService.requestDeletion(userId);
    }

    /**
     * 停用用户（Story 3.2，AC1/AC2/AC4）：①经 auth service 置 DEACTIVATED + 撤 refresh（即时不可登录/刷新）
     * ②经 consult service 强关进行中会话 ③同事务写审计 USER_DEACTIVATED。原因必填。
     */
    @Transactional
    public void deactivate(long userId, String reason, long actorAccountId) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("停用原因不能为空").code("admin.err.user.deactivateReasonRequired");
        }
        // 仅普通用户。
        accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        authService.deactivateUser(userId);
        consultInterrupt.interruptByUser(userId);
        auditService.record(actorAccountId, AuditActions.USER_DEACTIVATED, "USER",
                String.valueOf(userId), "停用用户（原因：" + reason.trim() + "）");
    }

    /** 重新激活用户（Story 3.2，AC5）：恢复登录权 + 写审计 USER_REACTIVATED。 */
    @Transactional
    public void reactivate(long userId, long actorAccountId) {
        accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        authService.reactivateUser(userId);
        auditService.record(actorAccountId, AuditActions.USER_REACTIVATED, "USER",
                String.valueOf(userId), "重新激活用户");
    }

    /** bug 20260701-164：后台用户管理分页列出全部普通用户（id 倒序，最近注册在前），供列表浏览。 */
    @Transactional(readOnly = true)
    public Page<AdminUserRow> list(Pageable pageable) {
        return accountQuery.listUsers(pageable).map(this::toRow);
    }

    /** 按用户 id 或注册邮箱搜索普通用户（USER）。命中 0 或 1 条。 */
    @Transactional(readOnly = true)
    public List<AdminUserRow> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        Optional<User> hit = q.chars().allMatch(Character::isDigit)
                ? safeById(q)
                : accountQuery.findUserByEmail(q);
        return hit.map(u -> List.of(toRow(u))).orElseGet(List::of);
    }

    /**
     * 按**手机号是否已填写**筛选（V1.1.6 Story 11.4 · AB-11A）。
     *
     * <p>供运营挑催填名单。判据见 {@code UserRepository#findByRoleAndPhoneFilled} ——
     * NULL 与空串都算未填写。
     */
    @Transactional(readOnly = true)
    public Page<AdminUserRow> listByPhoneFilled(boolean filled, Pageable pageable) {
        return users.findByRoleAndPhoneFilled(com.tailtopia.auth.domain.Role.USER, filled, pageable)
                .map(this::toRow);
    }

    /**
     * 召回名单导出（Story 11.4）。
     *
     * <p>🛡 **不自动剔除已封号账号，但每行必须标注账号状态** —— 运营有时确实要联系已封号用户，
     * 但不标注就等于让他在不知情的情况下发召回。
     *
     * <p>🔴 **导出记审计**（PRD 未要求，本 story 加的）：PII 批量出库不留痕，
     * 事后无从回答"这份名单是谁什么时候导的"。
     *
     * @return CSV 文本（首行表头）
     */
    // ⚠️ **不能标 readOnly** —— 本方法要写审计行。第一版写成了 readOnly=true，
    //    结果导出直接 500（`cannot execute INSERT in a read-only transaction`）：
    //    读的部分没问题，是那条审计插入被只读事务挡了。
    @Transactional
    public String exportRecallList(long actorAccountId, boolean filled) {
        List<User> rows = users.findAllByRoleAndPhoneFilled(
                com.tailtopia.auth.domain.Role.USER, filled);
        StringBuilder csv = new StringBuilder("user_id,display_name,phone,account_status\n");
        for (User u : rows) {
            boolean deleted = u.getDeletedAt() != null;
            String name = deleted ? u.getDeletedDisplayName() : currentName(u);
            // 账号状态：正常 / 已停用 / 已注销 —— 由运营自行判断是否纳入触达。
            String status = deleted ? "DELETED" : (deactivated(u) ? "DEACTIVATED" : "ACTIVE");
            csv.append(u.getId()).append(',')
                    .append(csvCell(name)).append(',')
                    .append(csvCell(u.getPhone())).append(',')
                    .append(status).append('\n');
        }
        // ⚠️ 审计摘要里**只写条数与筛选条件，绝不写号码本身**。
        auditService.record(actorAccountId, "USER_PHONE_RECALL_EXPORT", "USER", null,
                "导出召回名单：filter=" + (filled ? "已填写" : "未填写") + " rows=" + rows.size());
        return csv.toString();
    }

    private static String csvCell(String raw) {
        if (raw == null) {
            return "";
        }
        // 逗号/引号/换行都要转义，否则一个昵称里的逗号就能把整份名单的列错开。
        String escaped = raw.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    /** 用户详情聚合（五块只读）。 */
    @Transactional(readOnly = true)
    public AdminUserDetailView detail(long userId) {
        return detail(userId, false);
    }

    /**
     * 用户详情聚合。
     *
     * @param includePhone 🛡 是否装入手机号。**false 时字段恒为 null，服务端就不下发** ——
     *                     只在模板里隐藏是不够的：数据已经到了浏览器，看源码就能拿到。
     */
    @Transactional(readOnly = true)
    public AdminUserDetailView detail(long userId, boolean includePhone) {
        User u = accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));

        List<AdminUserDetailView.PetRow> pets = profileService.findByOwnerId(userId)
                .map(AdminUserService::toPetRow)
                .map(List::of)
                .orElseGet(List::of);

        boolean deleted = u.getDeletedAt() != null;
        // 已注销：显示名/邮箱取注销前快照列（仅后台展示）；未注销：昵称优先（同 toRow）。
        String name = deleted ? u.getDeletedDisplayName() : currentName(u);
        String email = deleted ? u.getDeletedEmail() : u.getEmail();
        return new AdminUserDetailView(
                u.getId(), name, u.getNickname(), email, u.getCreatedAt(),
                deactivated(u), deleted,
                includePhone ? u.getPhone() : null,
                pawCoinWallet.balanceOf(userId), pets,
                contentService.listByAuthorForAdmin(userId),
                consultHistory.adminSessionMetadata(userId));
    }

    private Optional<User> safeById(String digits) {
        try {
            return accountQuery.findUserById(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private AdminUserRow toRow(User u) {
        boolean deleted = u.getDeletedAt() != null;
        // 已注销：读注销前快照列展示「谁注销了」；未注销：昵称优先（用户改名落 nickname，
        // display_name 是注册时刻快照，与 AccountQueryService.toAuthorView 同一兜底约定）。
        String name = deleted ? u.getDeletedDisplayName() : currentName(u);
        String email = deleted ? u.getDeletedEmail() : u.getEmail();
        // 🛡 列表只带"有没有填"这个布尔，不带号码本身 —— 少一处出现 PII 就少一个泄漏面。
        boolean phoneFilled = u.getPhone() != null && !u.getPhone().isBlank();
        return new AdminUserRow(u.getId(), name, email, u.getCreatedAt(), deactivated(u), deleted,
                phoneFilled);
    }

    private static String currentName(User u) {
        return u.getNickname() != null ? u.getNickname() : u.getDisplayName();
    }

    private static AdminUserDetailView.PetRow toPetRow(PetProfile p) {
        return new AdminUserDetailView.PetRow(p.getId(), p.getName(),
                p.getPetType() == null ? null : p.getPetType().name(), p.getBreed());
    }

    /** Story 3.2：读用户状态。 */
    private boolean deactivated(User u) {
        return u.getStatus() == UserStatus.DEACTIVATED;
    }
}
