package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewPriority;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.admin.moderation.dto.ManualReviewRow;
import com.tailtopia.admin.moderation.read.ViolationCountReader;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.moderation.ModerationDecision;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.service.CommentService.CommentModerationSummary;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：人工审核处置（Story 4.3）+ 评论多态分派（story 3）——通过/拒绝/超时 → 内容/评论委托 + 通知 + 审计；
 * 状态机幂等；评论降级项超时不受开关门控（R1）。
 */
class ManualReviewServiceTest {

    private com.tailtopia.admin.moderation.repository.ManualReviewItemRepository queue;
    private ContentService contentService;
    private CommentService commentService;
    private NotificationService notifications;
    private AdminAuditService auditService;
    private AdminSettingsService settingsService;
    private ViolationCountReader violationCounts;
    private ViolationCountService violationCountService;
    private ManualReviewService service;

    @BeforeEach
    void setUp() {
        queue = mock(com.tailtopia.admin.moderation.repository.ManualReviewItemRepository.class);
        contentService = mock(ContentService.class);
        commentService = mock(CommentService.class);
        notifications = mock(NotificationService.class);
        auditService = mock(AdminAuditService.class);
        settingsService = mock(AdminSettingsService.class);
        violationCounts = mock(ViolationCountReader.class);
        violationCountService = mock(ViolationCountService.class);
        service = new ManualReviewService(queue, contentService, commentService, notifications,
                auditService, settingsService, violationCounts, violationCountService);
    }

    private void stubSummary(long contentId, long authorId) {
        when(contentService.findSummary(contentId)).thenReturn(Optional.of(
                new ContentService.PostSummary(contentId, ContentType.DAILY, "x", false, authorId)));
    }

    private void stubCommentSummary(long commentId, long postId, long authorId, int version) {
        when(commentService.findModerationSummary(commentId)).thenReturn(Optional.of(
                new CommentModerationSummary(commentId, postId, authorId, "x", version,
                        CommentModerationStatus.UNDER_REVIEW)));
    }

    @Test
    void approvePublishesSilentlyAndAudits() {
        ManualReviewItem item = ManualReviewItem.pending(500L, Instant.now());
        when(queue.findById(9L)).thenReturn(Optional.of(item));
        stubSummary(500L, 42L);

        service.approve(9L, 7L);

        verify(contentService).approveReview(500L);
        // D-CM6（cm-7）：审核通过静默转正，不再给帖子作者发通知；审计仍记（解耦）。
        verify(notifications, never()).send(anyLong(), any(), any(), any(), any(), any());
        assertThat(item.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        verify(queue).save(item);
        verify(auditService).record(eq(7L), eq(AuditActions.CONTENT_REVIEW_APPROVED), eq("CONTENT_POST"),
                eq("500"), any());
    }

    @Test
    void rejectDiscardsNotifiesAndAudits() {
        ManualReviewItem item = ManualReviewItem.pending(501L, Instant.now());
        when(queue.findById(10L)).thenReturn(Optional.of(item));
        stubSummary(501L, 43L);

        service.reject(10L, 7L, new ModerationDecision("AD_SPAM", "广告引流"));

        verify(contentService).discardReview(501L);
        verify(notifications).send(eq(43L), eq(NotificationType.CONTENT_REVIEW_REJECTED), any(), any(), any(), any());
        assertThat(item.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        verify(auditService).record(eq(7L), eq(AuditActions.CONTENT_REVIEW_REJECTED), eq("CONTENT_POST"),
                eq("501"), any());
        // story 9 §5.1：帖子人工判定拒绝 → 累加作者 POST 计数（同事务）。
        verify(violationCountService).record(43L, ViolationType.POST);
    }

    @Test
    void decideOnNonPendingRejected() {
        ManualReviewItem item = ManualReviewItem.pending(502L, Instant.now());
        item.decide(ReviewStatus.APPROVED, 1L, Instant.now()); // 已终态
        when(queue.findById(11L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.approve(11L, 7L)).isInstanceOf(AppException.class);
        verify(contentService, never()).approveReview(anyLong());
    }

    @Test
    void scanTimeoutsSkipsPostItemsWhenDisabled() {
        // R1：开关关时帖子项维持门控——不处置（但查询照跑，以便扫评论项）。
        when(settingsService.isManualReviewEnabled()).thenReturn(false);
        ManualReviewItem expiredPost = ManualReviewItem.pending(600L, Instant.now().minusSeconds(86400 * 4));
        when(queue.findByStatusAndSubmittedAtBefore(eq(ReviewStatus.PENDING), any()))
                .thenReturn(List.of(expiredPost));

        int n = service.scanTimeouts(Instant.now());

        assertThat(n).isZero();
        verify(contentService, never()).discardReview(anyLong());
        assertThat(expiredPost.getStatus()).isEqualTo(ReviewStatus.PENDING); // 未处置
    }

    @Test
    void scanTimeoutsDiscardsExpiredPendingWhenEnabled() {
        when(settingsService.isManualReviewEnabled()).thenReturn(true);
        ManualReviewItem expired = ManualReviewItem.pending(600L, Instant.now().minusSeconds(86400 * 4));
        when(queue.findByStatusAndSubmittedAtBefore(eq(ReviewStatus.PENDING), any()))
                .thenReturn(List.of(expired));
        stubSummary(600L, 44L);

        int n = service.scanTimeouts(Instant.now());

        assertThat(n).isEqualTo(1);
        verify(contentService).discardReview(600L);
        assertThat(expired.getStatus()).isEqualTo(ReviewStatus.TIMED_OUT);
        // cm-7：超时丢弃发新 CONTENT_REVIEW_TIMED_OUT（§8.8 文案不同），不再复用 REJECTED。
        verify(notifications).send(eq(44L), eq(NotificationType.CONTENT_REVIEW_TIMED_OUT), any(), any(), any(), any());
        verify(auditService).record(eq(null), eq(AuditActions.CONTENT_REVIEW_TIMED_OUT), eq("CONTENT_POST"),
                eq("600"), any());
        // story 9 §5.1：帖子队列超时丢弃 → 累加作者 POST 计数。
        verify(violationCountService).record(44L, ViolationType.POST);
    }

    // ===== story 3：评论多态分派（AC-B4/B5/B8） =====

    @Test
    void approveCommentDelegatesAndAuditsObjectTypeComment() {
        ManualReviewItem it = ManualReviewItem.pendingComment(700L, 1, Instant.now());
        when(queue.findById(20L)).thenReturn(Optional.of(it));
        stubCommentSummary(700L, 9L, 55L, 1);

        service.approve(20L, 7L);

        verify(commentService).approveComment(700L); // 转 VISIBLE + 此刻发新评论事件在 CommentService 内
        assertThat(it.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        // D-CM6 正向静默：不给评论作者发「通过」通知。
        verify(notifications, never()).send(anyLong(), any(), any(), any(), any(), any());
        verify(auditService).record(eq(7L), eq(AuditActions.CONTENT_REVIEW_APPROVED), eq("COMMENT"),
                eq("700"), any());
    }

    @Test
    void rejectCommentDelegatesNotifiesRemovedAndAudits() {
        ManualReviewItem it = ManualReviewItem.pendingComment(701L, 1, Instant.now());
        when(queue.findById(21L)).thenReturn(Optional.of(it));
        stubCommentSummary(701L, 9L, 56L, 1);

        service.reject(21L, 7L, new ModerationDecision("HARASSMENT", null));

        verify(commentService).rejectComment(701L);
        assertThat(it.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        // 复用 CONTENT_REMOVED，深链 postId=9。
        verify(notifications).send(eq(56L), eq(NotificationType.CONTENT_REMOVED), any(), any(), any(), eq("9"));
        verify(auditService).record(eq(7L), eq(AuditActions.CONTENT_REVIEW_REJECTED), eq("COMMENT"),
                eq("701"), any());
        // story 9 §5.1：评论人工审核拒绝【不计入】违规计数（COMMENT 仅 FR-55A 巡查下架计，非此路径）。
        verify(violationCountService, never()).record(anyLong(), any());
    }

    @Test
    void scanTimeoutsProcessesCommentItemsEvenWhenDisabled() {
        // R1：评论降级项无条件纳入超时扫描（不受开关门控）。
        when(settingsService.isManualReviewEnabled()).thenReturn(false);
        ManualReviewItem comment = ManualReviewItem.pendingComment(702L, 1, Instant.now().minusSeconds(86400 * 4));
        when(queue.findByStatusAndSubmittedAtBefore(eq(ReviewStatus.PENDING), any()))
                .thenReturn(List.of(comment));
        stubCommentSummary(702L, 9L, 57L, 1);

        int n = service.scanTimeouts(Instant.now());

        assertThat(n).isEqualTo(1);
        verify(commentService).rejectComment(702L);
        assertThat(comment.getStatus()).isEqualTo(ReviewStatus.TIMED_OUT);
        verify(auditService).record(eq(null), eq(AuditActions.CONTENT_REVIEW_TIMED_OUT), eq("COMMENT"),
                eq("702"), any());
    }

    @Test
    void staleCommentResultDiscardedWithoutTouchingComment() {
        // AC-B8（休眠）：入队版本 ≠ 当前版本 → 静默丢弃，不改评论态、不通知。
        ManualReviewItem it = ManualReviewItem.pendingComment(703L, 1, Instant.now());
        when(queue.findById(22L)).thenReturn(Optional.of(it));
        stubCommentSummary(703L, 9L, 58L, 2); // 当前版本 2 ≠ 入队 1

        service.approve(22L, 7L);

        verify(commentService, never()).approveComment(anyLong());
        verify(notifications, never()).send(anyLong(), any(), any(), any(), any(), any());
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    // ===== story 8：队列优先级排序 + 运营改优先级（§5.1，AC2/AC4） =====

    /** 工厂不设 id（DB 生成）；行投影要拆箱 getId()，故测试用反射补一个 id。 */
    private static ManualReviewItem itemWithId(long id, long contentId, ReviewPriority p) {
        ManualReviewItem it = ManualReviewItem.pending(contentId, Instant.now(), p);
        try {
            var f = ManualReviewItem.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(it, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return it;
    }

    @Test
    void pendingQueueUsesPriorityOrderedRepoAndPassesPriorityThrough() {
        // 仓储按 priority 升序 + submitted_at 升序返回（DB 排序）；service 透传顺序 + 填 priority 到行。
        ManualReviewItem p0 = itemWithId(1L, 800L, ReviewPriority.P0);
        ManualReviewItem p1 = itemWithId(2L, 801L, ReviewPriority.P1);
        ManualReviewItem p2 = itemWithId(3L, 802L, ReviewPriority.P2);
        when(queue.findByStatusOrderByPriorityAscSubmittedAtAsc(ReviewStatus.PENDING))
                .thenReturn(List.of(p0, p1, p2));
        stubSummary(800L, 61L);
        stubSummary(801L, 62L);
        stubSummary(802L, 63L);

        List<ManualReviewRow> rows = service.pendingQueue();

        // 用 priority 排序仓储方法（非旧的 submittedAt-only）。
        verify(queue).findByStatusOrderByPriorityAscSubmittedAtAsc(ReviewStatus.PENDING);
        assertThat(rows).extracting(ManualReviewRow::priority)
                .containsExactly(ReviewPriority.P0, ReviewPriority.P1, ReviewPriority.P2);
        // 违规计数端口被查询（story 9 未接入 → 展示空）。
        assertThat(rows).allSatisfy(r -> assertThat(r.strikes()).isNotNull());
    }

    @Test
    void changePriorityUpdatesAndAudits() {
        ManualReviewItem item = ManualReviewItem.pending(810L, Instant.now(), ReviewPriority.P2);
        when(queue.findById(30L)).thenReturn(Optional.of(item));

        service.changePriority(30L, ReviewPriority.P0, 7L);

        assertThat(item.getPriority()).isEqualTo(ReviewPriority.P0);
        verify(queue).save(item);
        verify(auditService).record(eq(7L), eq(AuditActions.REVIEW_PRIORITY_CHANGED),
                eq("MANUAL_REVIEW_ITEM"), eq("30"), any());
    }

    @Test
    void changePriorityRejectedOnNonPending() {
        ManualReviewItem item = ManualReviewItem.pending(811L, Instant.now(), ReviewPriority.P1);
        item.decide(ReviewStatus.APPROVED, 1L, Instant.now()); // 已终态
        when(queue.findById(31L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.changePriority(31L, ReviewPriority.P0, 7L))
                .isInstanceOf(AppException.class);
        verify(auditService, never()).record(anyLong(), eq(AuditActions.REVIEW_PRIORITY_CHANGED),
                any(), any(), any());
    }
}
