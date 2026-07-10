package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.admin.usermgmt.domain.DeletionType;
import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.admin.usermgmt.dto.AdminUserRow;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.AuthService;
import com.tailtopia.consult.service.ConsultHistoryService;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.content.service.ContentService;
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

    public AdminUserService(AccountQueryService accountQuery, ProfileService profileService,
            ContentService contentService, ConsultHistoryService consultHistory,
            AuthService authService, ConsultInterruptService consultInterrupt,
            AdminAuditService auditService, AccountDeletionService accountDeletionService) {
        this.accountQuery = accountQuery;
        this.profileService = profileService;
        this.contentService = contentService;
        this.consultHistory = consultHistory;
        this.authService = authService;
        this.consultInterrupt = consultInterrupt;
        this.auditService = auditService;
        this.accountDeletionService = accountDeletionService;
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
            throw AppException.validation("请选择删除类型（注销 / 违规）");
        }
        if (note == null || note.isBlank()) {
            throw AppException.validation("删除备注不能为空");
        }
        User target = accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在"));
        // 已注销账号仅展示，禁止重复删除（否则重写审计 + 重触发级联）。
        if (target.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，无需重复删除");
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
            throw AppException.validation("停用原因不能为空");
        }
        // 仅普通用户。
        accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在"));
        authService.deactivateUser(userId);
        consultInterrupt.interruptByUser(userId);
        auditService.record(actorAccountId, AuditActions.USER_DEACTIVATED, "USER",
                String.valueOf(userId), "停用用户（原因：" + reason.trim() + "）");
    }

    /** 重新激活用户（Story 3.2，AC5）：恢复登录权 + 写审计 USER_REACTIVATED。 */
    @Transactional
    public void reactivate(long userId, long actorAccountId) {
        accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在"));
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

    /** 用户详情聚合（五块只读）。 */
    @Transactional(readOnly = true)
    public AdminUserDetailView detail(long userId) {
        User u = accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在"));

        List<AdminUserDetailView.PetRow> pets = profileService.findByOwnerId(userId)
                .map(AdminUserService::toPetRow)
                .map(List::of)
                .orElseGet(List::of);

        boolean deleted = u.getDeletedAt() != null;
        // 已注销：显示名/邮箱取注销前快照列（仅后台展示）。
        String name = deleted ? u.getDeletedDisplayName() : u.getDisplayName();
        String email = deleted ? u.getDeletedEmail() : u.getEmail();
        return new AdminUserDetailView(
                u.getId(), name, u.getNickname(), email, u.getCreatedAt(),
                deactivated(u), deleted, pets,
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
        // 已注销：读注销前快照列展示「谁注销了」；未注销：读原列。
        String name = deleted ? u.getDeletedDisplayName() : u.getDisplayName();
        String email = deleted ? u.getDeletedEmail() : u.getEmail();
        return new AdminUserRow(u.getId(), name, email, u.getCreatedAt(), deactivated(u), deleted);
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
