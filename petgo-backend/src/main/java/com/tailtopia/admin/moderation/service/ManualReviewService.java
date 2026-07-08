package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewContentType;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.admin.moderation.dto.ManualReviewRow;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.service.CommentService.CommentModerationSummary;
import com.tailtopia.content.service.ContentService;
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

    public ManualReviewService(ManualReviewItemRepository queue, ContentService contentService,
            CommentService commentService, NotificationService notifications,
            AdminAuditService auditService, AdminSettingsService settingsService) {
        this.queue = queue;
        this.contentService = contentService;
        this.commentService = commentService;
        this.notifications = notifications;
        this.auditService = auditService;
        this.settingsService = settingsService;
    }

    /** PENDING 队列（提交时间升序），含内容/评论预览 + 24h 超期高亮标记（AC4/AC7）。 */
    @Transactional(readOnly = true)
    public List<ManualReviewRow> pendingQueue() {
        Instant overdueCutoff = Instant.now().minus(OVERDUE_THRESHOLD);
        return queue.findByStatusOrderBySubmittedAtAsc(ReviewStatus.PENDING).stream()
                .map(it -> toRow(it, overdueCutoff))
                .toList();
    }

    private ManualReviewRow toRow(ManualReviewItem it, Instant overdueCutoff) {
        boolean overdue = it.getSubmittedAt().isBefore(overdueCutoff);
        if (it.getContentType() == ReviewContentType.COMMENT) {
            var summary = commentService.findModerationSummary(it.getContentId()).orElse(null);
            return new ManualReviewRow(it.getId(), it.getContentId(), ReviewContentType.COMMENT,
                    null,
                    summary == null ? "(评论不存在)" : summary.textPreview(),
                    summary == null ? null : summary.authorId(),
                    it.getSubmittedAt(), overdue);
        }
        var summary = contentService.findSummary(it.getContentId()).orElse(null);
        return new ManualReviewRow(it.getId(), it.getContentId(), ReviewContentType.CONTENT_POST,
                summary == null ? null : summary.type(),
                summary == null ? "(内容不存在)" : summary.textPreview(),
                summary == null ? null : summary.authorId(),
                it.getSubmittedAt(), overdue);
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

    /** 拒绝：帖子丢弃 / 评论转 REJECTED（永不发新评论事件）+ 通知作者 + 审计。仅 PENDING 可处置。 */
    @Transactional
    public void reject(long itemId, long actorAccountId) {
        ManualReviewItem it = requirePending(itemId);
        if (it.getContentType() == ReviewContentType.COMMENT) {
            if (staleDiscard(it, actorAccountId)) {
                return;
            }
            rejectComment(it, actorAccountId, AuditActions.CONTENT_REVIEW_REJECTED, ReviewStatus.REJECTED,
                    "评论人工审核拒绝（队列项 #" + itemId + "）");
            return;
        }
        contentService.discardReview(it.getContentId());
        notifyPostAuthor(it.getContentId(), NotificationType.CONTENT_REVIEW_REJECTED,
                "内容未通过审核", "您的内容未通过人工审核，未予发布。");
        it.decide(ReviewStatus.REJECTED, actorAccountId, Instant.now());
        queue.save(it);
        auditService.record(actorAccountId, AuditActions.CONTENT_REVIEW_REJECTED, "CONTENT_POST",
                String.valueOf(it.getContentId()), "人工审核拒绝（队列项 #" + itemId + "）");
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
                contentService.discardReview(it.getContentId());
                notifyPostAuthor(it.getContentId(), NotificationType.CONTENT_REVIEW_TIMED_OUT,
                        "内容未通过审核", "您的内容超过审核时限未处理，未予发布。");
                it.decide(ReviewStatus.TIMED_OUT, null, now);
                queue.save(it);
                auditService.record(null, AuditActions.CONTENT_REVIEW_TIMED_OUT, "CONTENT_POST",
                        String.valueOf(it.getContentId()), "人工审核超时自动丢弃（队列项 #" + it.getId() + "）");
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
