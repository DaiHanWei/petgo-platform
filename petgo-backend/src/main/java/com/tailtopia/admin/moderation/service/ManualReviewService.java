package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.admin.moderation.dto.ManualReviewRow;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
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
 * 人工审核队列处置（Story 4.3，AB-3C）。仅在开关激活后产生队列项；本服务负责浏览 + 通过/拒绝 + 超时丢弃。
 * 内容状态变更经 {@link ContentService}（禁直读 content repo）；处置写审计 + 通知作者。状态机幂等（仅处置 PENDING）。
 */
@Service
public class ManualReviewService {

    private static final Logger log = LoggerFactory.getLogger(ManualReviewService.class);
    private static final Duration OVERDUE_THRESHOLD = Duration.ofHours(24);
    private static final Duration TIMEOUT_THRESHOLD = Duration.ofDays(3);

    private final ManualReviewItemRepository queue;
    private final ContentService contentService;
    private final NotificationService notifications;
    private final AdminAuditService auditService;
    private final AdminSettingsService settingsService;

    public ManualReviewService(ManualReviewItemRepository queue, ContentService contentService,
            NotificationService notifications, AdminAuditService auditService,
            AdminSettingsService settingsService) {
        this.queue = queue;
        this.contentService = contentService;
        this.notifications = notifications;
        this.auditService = auditService;
        this.settingsService = settingsService;
    }

    /** PENDING 队列（提交时间升序），含内容预览 + 24h 超期高亮标记（AC4/AC7）。 */
    @Transactional(readOnly = true)
    public List<ManualReviewRow> pendingQueue() {
        Instant overdueCutoff = Instant.now().minus(OVERDUE_THRESHOLD);
        return queue.findByStatusOrderBySubmittedAtAsc(ReviewStatus.PENDING).stream()
                .map(it -> toRow(it, overdueCutoff))
                .toList();
    }

    private ManualReviewRow toRow(ManualReviewItem it, Instant overdueCutoff) {
        var summary = contentService.findSummary(it.getContentId()).orElse(null);
        return new ManualReviewRow(it.getId(), it.getContentId(),
                summary == null ? null : summary.type(),
                summary == null ? "(内容不存在)" : summary.textPreview(),
                summary == null ? null : summary.authorId(),
                it.getSubmittedAt(),
                it.getSubmittedAt().isBefore(overdueCutoff));
    }

    /** 通过：内容转 PUBLISHED（进 Feed）+ 通知作者 + 队列 APPROVED + 审计。仅 PENDING 可处置。 */
    @Transactional
    public void approve(long itemId, long actorAccountId) {
        ManualReviewItem it = requirePending(itemId);
        contentService.approveReview(it.getContentId());
        notifyAuthor(it.getContentId(), NotificationType.CONTENT_REVIEW_APPROVED,
                "内容已通过审核", "您的内容已通过人工审核，现已发布。");
        it.decide(ReviewStatus.APPROVED, actorAccountId, Instant.now());
        queue.save(it);
        auditService.record(actorAccountId, AuditActions.CONTENT_REVIEW_APPROVED, "CONTENT_POST",
                String.valueOf(it.getContentId()), "人工审核通过（队列项 #" + itemId + "）");
    }

    /** 拒绝：内容丢弃（软删）+ 通知作者 + 队列 REJECTED + 审计。仅 PENDING 可处置。 */
    @Transactional
    public void reject(long itemId, long actorAccountId) {
        ManualReviewItem it = requirePending(itemId);
        contentService.discardReview(it.getContentId());
        notifyAuthor(it.getContentId(), NotificationType.CONTENT_REVIEW_REJECTED,
                "内容未通过审核", "您的内容未通过人工审核，未予发布。");
        it.decide(ReviewStatus.REJECTED, actorAccountId, Instant.now());
        queue.save(it);
        auditService.record(actorAccountId, AuditActions.CONTENT_REVIEW_REJECTED, "CONTENT_POST",
                String.valueOf(it.getContentId()), "人工审核拒绝（队列项 #" + itemId + "）");
    }

    /**
     * 超时扫描（AC6）：开关关 → 早返回不处置；开 → 将 submitted_at 超 3 天仍 PENDING 的项置 TIMED_OUT
     * + 丢弃内容 + 通知作者。状态机幂等去重（仅 PENDING；置终态后不再被扫）。返回本次处置数。
     */
    @Transactional
    public int scanTimeouts(Instant now) {
        if (!settingsService.isManualReviewEnabled()) {
            return 0; // 未激活：定时器空转
        }
        Instant cutoff = now.minus(TIMEOUT_THRESHOLD);
        List<ManualReviewItem> expired = queue.findByStatusAndSubmittedAtBefore(ReviewStatus.PENDING, cutoff);
        for (ManualReviewItem it : expired) {
            contentService.discardReview(it.getContentId());
            notifyAuthor(it.getContentId(), NotificationType.CONTENT_REVIEW_REJECTED,
                    "内容未通过审核", "您的内容超过审核时限未处理，未予发布。");
            it.decide(ReviewStatus.TIMED_OUT, null, now);
            queue.save(it);
            auditService.record(null, AuditActions.CONTENT_REVIEW_TIMED_OUT, "CONTENT_POST",
                    String.valueOf(it.getContentId()), "人工审核超时自动丢弃（队列项 #" + it.getId() + "）");
        }
        if (!expired.isEmpty()) {
            log.info("人工审核超时扫描处置 count={}", expired.size());
        }
        return expired.size();
    }

    private ManualReviewItem requirePending(long itemId) {
        ManualReviewItem it = queue.findById(itemId)
                .orElseThrow(() -> AppException.notFound("审核队列项不存在"));
        if (it.getStatus() != ReviewStatus.PENDING) {
            throw AppException.validation("该队列项已处置");
        }
        return it;
    }

    private void notifyAuthor(long contentId, NotificationType type, String title, String body) {
        contentService.findSummary(contentId).map(s -> s.authorId()).ifPresent(authorId -> {
            if (authorId != null) {
                notifications.send(authorId, type, title, body, null, null);
            }
        });
    }
}
