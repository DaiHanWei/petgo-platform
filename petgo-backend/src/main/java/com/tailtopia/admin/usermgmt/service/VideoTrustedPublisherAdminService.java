package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.usermgmt.domain.VideoTrustedPublisher;
import com.tailtopia.admin.usermgmt.repository.VideoTrustedPublisherRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.VideoTrustedPublisherService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频免审名单的维护与查询（专题线 video · Story 1.1 · FR-22，决策 V-25）。
 *
 * <p>同时作为 content 模块 {@link VideoTrustedPublisherService} 端口的实现 bean（同 {@code ManualReviewGateImpl}
 * 的依赖方向：content 定义接口、admin 实现）。加入 / 移出均幂等，与审计在同一事务内。
 */
@Service
public class VideoTrustedPublisherAdminService implements VideoTrustedPublisherService {

    /** 名单列表行（昵称 / 等级 / 加入人显示名均已取好，模板只做展示）。 */
    public record Row(long userId, String nickname, int vipLevel, String addedBy, Instant addedAt) {
    }

    /** 加入 / 移出的结果：{@code changed=false} 表示本来就（不）在名单里，属幂等无变化。 */
    public record Outcome(boolean changed) {
    }

    private final VideoTrustedPublisherRepository repo;
    private final AccountQueryService accountQuery;
    private final AdminAccountRepository adminAccounts;
    private final AdminAuditService auditService;

    public VideoTrustedPublisherAdminService(VideoTrustedPublisherRepository repo,
            AccountQueryService accountQuery, AdminAccountRepository adminAccounts,
            AdminAuditService auditService) {
        this.repo = repo;
        this.accountQuery = accountQuery;
        this.adminAccounts = adminAccounts;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTrusted(long userId) {
        return repo.existsById(userId);
    }

    /** 加入名单（原子幂等）。已在名单 → 不改、不写审计，返回 {@code changed=false}。 */
    @Transactional
    public Outcome add(long userId, long actorAccountId) {
        requireLiveUser(userId);
        if (repo.insertIfAbsent(userId, actorAccountId) == 0) {
            return new Outcome(false);
        }
        auditService.record(actorAccountId, AuditActions.VIDEO_TRUSTED_ADD, "USER",
                String.valueOf(userId), "加入视频免审名单 userId=" + userId);
        return new Outcome(true);
    }

    /** 移出名单（原子幂等）。本不在名单 → 不改、不写审计，返回 {@code changed=false}。移出不要求账号仍有效。 */
    @Transactional
    public Outcome remove(long userId, long actorAccountId) {
        if (repo.deleteByUserId(userId) == 0) {
            return new Outcome(false);
        }
        auditService.record(actorAccountId, AuditActions.VIDEO_TRUSTED_REMOVE, "USER",
                String.valueOf(userId), "移出视频免审名单 userId=" + userId);
        return new Outcome(true);
    }

    /**
     * 注销级联（D1/D2）：账号注销时把它移出名单。由 {@code AccountDeletionService} 调用，系统动作、不写后台审计。
     *
     * <p>🔴 不能靠 FK 的 ON DELETE CASCADE：注销是就地匿名化，users 行不删，CASCADE 永远不触发（code-review 2026-10-16）。
     */
    @Transactional
    public void purgeForAccountDeletion(long userId) {
        repo.deleteByUserId(userId);
    }

    /** 名单列表（加入时间倒序）。用户与加入人都批量取，避免逐行查库。 */
    @Transactional(readOnly = true)
    public Page<Row> page(Pageable pageable) {
        Page<VideoTrustedPublisher> page = repo.findAllByOrderByCreatedAtDescUserIdDesc(pageable);
        List<Long> userIds = page.getContent().stream().map(VideoTrustedPublisher::getUserId).toList();
        Map<Long, User> users = accountQuery.findAccountsByIds(userIds);
        List<Long> adminIds = page.getContent().stream().map(VideoTrustedPublisher::getCreatedByAdminId).distinct()
                .toList();
        Map<Long, String> adminNames = adminAccounts.findAllById(adminIds).stream()
                .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getDisplayName, (a, b) -> a));
        return page.map(p -> {
            User u = users.get(p.getUserId());
            return new Row(p.getUserId(),
                    u == null ? null : u.getNickname(),
                    u == null ? 0 : u.getVipLevel(),
                    adminNames.getOrDefault(p.getCreatedByAdminId(), "#" + p.getCreatedByAdminId()),
                    p.getCreatedAt());
        });
    }

    private void requireLiveUser(long userId) {
        User target = accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        if (target.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，不可加入视频免审名单").code("admin.err.user.deletedNoVideoTrusted");
        }
    }
}
