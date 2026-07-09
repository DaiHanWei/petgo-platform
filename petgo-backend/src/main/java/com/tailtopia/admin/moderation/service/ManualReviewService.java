package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewContentType;
import com.tailtopia.admin.moderation.domain.ReviewPriority;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.admin.moderation.dto.ManualReviewRow;
import com.tailtopia.admin.moderation.dto.ViolationCounts;
import com.tailtopia.admin.moderation.read.ViolationCountReader;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.content.moderation.ModerationDecision;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.service.CommentService.CommentModerationSummary;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工审核队列处置（Story 4.3，AB-3C）+ 评论多态分派（内容审核 story 3，§5.3）。仅处置 PENDING（状态机幂等）。
 * 内容/评论状态变更经 content 侧门面（{@link ContentService} / {@link CommentService}，禁直读 content repo）。
 *
 * <p><b>多态（D-CM-C1）</b>：按 {@code content_type} 分流——{@code CONTENT_POST} 沿用 story 2 语义（不变）；
 * {@code COMMENT} 走评论审核态迁移 + 通知时机 G4（approve 转 VISIBLE 时才发新评论事件）。
 */
@Service
public class ManualReviewService {

    private static final Logger log = LoggerFactory.getLogger(ManualReviewService.class);
    private static final Duration OVERDUE_THRESHOLD = Duration.ofHours(24);
    private static final Duration TIMEOUT_THRESHOLD = Duration.ofDays(3);

    /** §8.5 评论移除通知（临时中文 literal；i18n 归 story 7）。复用 CONTENT_REMOVED 类型避免动 CHECK 迁移。 */
    private static final String COMMENT_REMOVED_TITLE = "内容已被移除";
    private static final String COMMENT_REMOVED_BODY = "你发布的评论因违反社区规范已被移除";

    private final ManualReviewItemRepository queue;
    private final ContentService contentService;
    private final CommentService commentService;
    private final NotificationService notifications;
    private final AdminAuditService auditService;
    private final AdminSettingsService settingsService;
    private final ViolationCountReader violationCounts;
    private final ViolationCountService violationCountService;

    public ManualReviewService(ManualReviewItemRepository queue, ContentService contentService,
            CommentService commentService, NotificationService notifications,
            AdminAuditService auditService, AdminSettingsService settingsService,
            ViolationCountReader violationCounts, ViolationCountService violationCountService) {
        this.queue = queue;
        this.contentService = contentService;
        this.commentService = commentService;
        this.notifications = notifications;
        this.auditService = auditService;
        this.settingsService = settingsService;
        this.violationCounts = violationCounts;
        this.violationCountService = violationCountService;
    }

    /**
     * PENDING 队列（story 8 §5.1：优先级升序 + 同级提交时间升序），含内容/评论预览 + 24h 超期高亮标记（AC7）
     * + 作者累计违规计数（§5.4，读 {@link ViolationCountReader}，只读展示不触发限制）。
     */
    @Transactional(readOnly = true)
    public List<ManualReviewRow> pendingQueue() {
        Instant overdueCutoff = Instant.now().minus(OVERDUE_THRESHOLD);
        return queue.findByStatusOrderByPriorityAscSubmittedAtAsc(ReviewStatus.PENDING).stream()
                .map(it -> toRow(it, overdueCutoff))
                .toList();
    }

    private ManualReviewRow toRow(ManualReviewItem it, Instant overdueCutoff) {
        boolean overdue = it.getSubmittedAt().isBefore(overdueCutoff);
        if (it.getContentType() == ReviewContentType.COMMENT) {
            var summary = commentService.findModerationSummary(it.getContentId()).orElse(null);
            Long authorId = summary == null ? null : summary.authorId();
            return new ManualReviewRow(it.getId(), it.getContentId(), ReviewContentType.COMMENT,
                    null,
                    summary == null ? "(评论不存在)" : summary.textPreview(),
                    authorId,
                    it.getSubmittedAt(), overdue, it.getPriority(), strikesFor(authorId));
        }
        var summary = contentService.findSummary(it.getContentId()).orElse(null);
        Long authorId = summary == null ? null : summary.authorId();
        return new ManualReviewRow(it.getId(), it.getContentId(), ReviewContentType.CONTENT_POST,
                summary == null ? null : summary.type(),
                summary == null ? "(内容不存在)" : summary.textPreview(),
                authorId,
                it.getSubmittedAt(), overdue, it.getPriority(), strikesFor(authorId));
    }

    /** 作者累计违规计数快照（§5.4）；作者不可解析时空计数（展示「—」）。 */
    private ViolationCounts strikesFor(Long authorId) {
        if (authorId == null) {
            return ViolationCounts.empty();
        }
        return ViolationCounts.fromMap(violationCounts.countsFor(authorId));
    }

    /** 通过：帖子转 PUBLISHED / 评论转 VISIBLE（此刻发新评论事件）+ 审计。仅 PENDING 可处置。 */
    @Transactional
    public void approve(long itemId, long actorAccountId) {
        ManualReviewItem it = requirePending(itemId);
        if (it.getContentType() == ReviewContentType.COMMENT) {
            if (staleDiscard(it, actorAccountId)) {
                return; // D-CM3：陈旧结果作废（休眠；V1 不可达）
            }
            commentService.approveComment(it.getContentId()); // VISIBLE + 此刻发 ContentCommentedEvent（G4）
            it.decide(ReviewStatus.APPROVED, actorAccountId, Instant.now());
            queue.save(it);
            // D-CM6：正向静默——不给评论作者发「通过」通知。
            auditService.record(actorAccountId, AuditActions.CONTENT_REVIEW_APPROVED, "COMMENT",
                    String.valueOf(it.getContentId()), "评论人工审核通过（队列项 #" + itemId + "）");
            return;
        }
        contentService.approveReview(it.getContentId());
        // D-CM6：正向静默——审核通过不给帖子作者发通知（作者本就在「我的发布」可见）。审计仍记（解耦）。
        it.decide(ReviewStatus.APPROVED, actorAccountId, Instant.now());
        queue.save(it);
        auditService.record(actorAccountId, AuditActions.CONTENT_REVIEW_APPROVED, "CONTENT_POST",
                String.valueOf(it.getContentId()), "人工审核通过（队列项 #" + itemId + "）");
    }

    /**
     * 拒绝：帖子丢弃 / 评论转 REJECTED（永不发新评论事件）+ 通知作者 + 审计。仅 PENDING 可处置。
     *
     * <p>story 8 §5.2：{@code decision}（判定依据/备注）由处置表单采集，折叠进 append-only 审计 summary
     * （无内容正文原文）；作者拒绝通知文案不变（本版本不提供具体驳回原因/申诉，§5.5）。{@code decision} 可空。
     */
    @Transactional
    public void reject(long itemId, long actorAccountId, ModerationDecision decision) {
        ModerationDecision d = decision == null ? ModerationDecision.none() : decision;
        ManualReviewItem it = requirePending(itemId);
        if (it.getContentType() == ReviewContentType.COMMENT) {
            if (staleDiscard(it, actorAccountId)) {
                return;
            }
            rejectComment(it, actorAccountId, AuditActions.CONTENT_REVIEW_REJECTED, ReviewStatus.REJECTED,
                    "评论人工审核拒绝（队列项 #" + itemId + "）；" + d.auditFragment());
            return;
        }
        Long authorId = postAuthorId(it.getContentId());
        contentService.discardReview(it.getContentId());
        notifyPostAuthor(it.getContentId(), NotificationType.CONTENT_REVIEW_REJECTED,
                "内容未通过审核", "您的内容未通过人工审核，未予发布。");
        it.decide(ReviewStatus.REJECTED, actorAccountId, Instant.now());
        queue.save(it);
        auditService.record(actorAccountId, AuditActions.CONTENT_REVIEW_REJECTED, "CONTENT_POST",
                String.valueOf(it.getContentId()), "人工审核拒绝（队列项 #" + itemId + "）；" + d.auditFragment());
        // story 9 §5.1：帖子 FR-12A 人工判定拒绝 = 人工判定违规 → 同事务累加 POST 计数。
        recordPostViolation(authorId);
    }

    /**
     * 调整队列项优先级（story 8，§5.1）。仅 {@code PENDING} 可改（复用 {@link #requirePending}）→ set → save →
     * 审计 {@code REVIEW_PRIORITY_CHANGED}（同事务，AC14）。
     */
    @Transactional
    public void changePriority(long itemId, ReviewPriority priority, long actorAccountId) {
        if (priority == null) {
            throw AppException.validation("优先级必填（P0 / P1 / P2）");
        }
        ManualReviewItem it = requirePending(itemId);
        ReviewPriority old = it.getPriority();
        it.changePriority(priority);
        queue.save(it);
        auditService.record(actorAccountId, AuditActions.REVIEW_PRIORITY_CHANGED, "MANUAL_REVIEW_ITEM",
                String.valueOf(itemId), "调整审核优先级 " + old + " → " + priority);
    }

    /**
     * 超时扫描（AC6 + R1）：<b>评论降级项无条件纳入超时扫描</b>（不受 {@code manual_review_enabled} 门控——
     * 否则开关关时降级评论永挂）；<b>帖子项维持开关门控</b>（开关关时不处置）。超 3 天仍 PENDING → 丢弃/REJECTED
     * + 通知作者。状态机幂等去重。返回本次处置数。
     */
    @Transactional
    public int scanTimeouts(Instant now) {
        boolean postGateEnabled = settingsService.isManualReviewEnabled();
        Instant cutoff = now.minus(TIMEOUT_THRESHOLD);
        List<ManualReviewItem> expired = queue.findByStatusAndSubmittedAtBefore(ReviewStatus.PENDING, cutoff);
        int processed = 0;
        for (ManualReviewItem it : expired) {
            if (it.getContentType() == ReviewContentType.COMMENT) {
                rejectComment(it, null, AuditActions.CONTENT_REVIEW_TIMED_OUT, ReviewStatus.TIMED_OUT,
                        "评论人工审核超时自动丢弃（队列项 #" + it.getId() + "）");
                processed++;
            } else if (postGateEnabled) {
                Long authorId = postAuthorId(it.getContentId());
                contentService.discardReview(it.getContentId());
                notifyPostAuthor(it.getContentId(), NotificationType.CONTENT_REVIEW_TIMED_OUT,
                        "内容未通过审核", "您的内容超过审核时限未处理，未予发布。");
                it.decide(ReviewStatus.TIMED_OUT, null, now);
                queue.save(it);
                auditService.record(null, AuditActions.CONTENT_REVIEW_TIMED_OUT, "CONTENT_POST",
                        String.valueOf(it.getContentId()), "人工审核超时自动丢弃（队列项 #" + it.getId() + "）");
                // story 9 §5.1：帖子 FR-12A 队列超时丢弃 = 人工判定违规 → 同事务累加 POST 计数。
                recordPostViolation(authorId);
                processed++;
            }
        }
        if (processed > 0) {
            log.info("人工审核超时扫描处置 count={}", processed);
        }
        return processed;
    }

    /** 评论拒绝/超时公共路径：转 REJECTED + 通知作者（CONTENT_REMOVED 深链 postId）+ 审计 objectType=COMMENT。 */
    private void rejectComment(ManualReviewItem it, Long actorAccountId, String auditAction,
            ReviewStatus terminal, String auditSummary) {
        long commentId = it.getContentId();
        CommentModerationSummary summary = commentService.findModerationSummary(commentId).orElse(null);
        commentService.rejectComment(commentId); // REJECTED，永不发新评论事件
        if (summary != null) {
            notifications.send(summary.authorId(), NotificationType.CONTENT_REMOVED,
                    COMMENT_REMOVED_TITLE, COMMENT_REMOVED_BODY, NotificationType.CONTENT_REMOVED.name(),
                    String.valueOf(summary.postId()));
        }
        it.decide(terminal, actorAccountId, Instant.now());
        queue.save(it);
        auditService.record(actorAccountId, auditAction, "COMMENT", String.valueOf(commentId), auditSummary);
    }

    /**
     * D-CM3 陈旧作废（§5.6，休眠）：入队版本 ≠ 评论当前版本 → 旧结果作废、不改评论态/不通知，队列项置终态移除。
     * V1 无评论编辑端点故 {@code contentVersion} 恒为 1、此分支不可达（防御式）。返回是否作为陈旧处理。
     */
    private boolean staleDiscard(ManualReviewItem it, long actorAccountId) {
        Integer enqueued = it.getContentVersion();
        if (enqueued == null) {
            return false;
        }
        CommentModerationSummary summary = commentService.findModerationSummary(it.getContentId()).orElse(null);
        if (summary != null && summary.contentVersion() != enqueued) {
            it.decide(ReviewStatus.REJECTED, actorAccountId, Instant.now()); // 静默移除队列，不动评论/不通知
            queue.save(it);
            return true;
        }
        return false;
    }

    /**
     * 注销联动（story 9，§5.5.2）：把注销用户在队列中的 PENDING 条目移出队列（置 TIMED_OUT 终态），对应内容
     * 不再发布（内容已由注销隐藏，作者已注销即对所有人不可见）。native 批量按内容作者子查询，幂等。
     */
    @Transactional
    public int removePendingForAuthor(long authorId) {
        return queue.deactivatePendingByAuthor(authorId);
    }

    /** 帖子作者 id（累加违规计数用）；内容不可解析则 null。 */
    private Long postAuthorId(long contentId) {
        return contentService.findSummary(contentId).map(ContentService.PostSummary::authorId).orElse(null);
    }

    /** 帖子人工判定违规 → 累加 POST 计数（§5.1；作者不可解析则跳过）。 */
    private void recordPostViolation(Long authorId) {
        if (authorId != null) {
            violationCountService.record(authorId, ViolationType.POST);
        }
    }

    private ManualReviewItem requirePending(long itemId) {
        ManualReviewItem it = queue.findById(itemId)
                .orElseThrow(() -> AppException.notFound("审核队列项不存在"));
        if (it.getStatus() != ReviewStatus.PENDING) {
            throw AppException.validation("该队列项已处置");
        }
        return it;
    }

    private void notifyPostAuthor(long contentId, NotificationType type, String title, String body) {
        contentService.findSummary(contentId).map(s -> s.authorId()).ifPresent(authorId -> {
            if (authorId != null) {
                notifications.send(authorId, type, title, body, null, null);
            }
        });
    }
}
