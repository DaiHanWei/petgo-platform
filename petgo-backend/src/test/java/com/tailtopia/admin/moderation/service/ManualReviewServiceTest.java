package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：人工审核处置（Story 4.3）——通过/拒绝/超时 → 内容委托 + 通知作者 + 审计；状态机幂等。 */
class ManualReviewServiceTest {

    private com.tailtopia.admin.moderation.repository.ManualReviewItemRepository queue;
    private ContentService contentService;
    private NotificationService notifications;
    private AdminAuditService auditService;
    private AdminSettingsService settingsService;
    private ManualReviewService service;

    @BeforeEach
    void setUp() {
        queue = mock(com.tailtopia.admin.moderation.repository.ManualReviewItemRepository.class);
        contentService = mock(ContentService.class);
        notifications = mock(NotificationService.class);
        auditService = mock(AdminAuditService.class);
        settingsService = mock(AdminSettingsService.class);
        service = new ManualReviewService(queue, contentService, notifications, auditService, settingsService);
    }

    private void stubSummary(long contentId, long authorId) {
        when(contentService.findSummary(contentId)).thenReturn(Optional.of(
                new ContentService.PostSummary(contentId, ContentType.DAILY, "x", false, authorId)));
    }

    @Test
    void approvePublishesNotifiesAndAudits() {
        ManualReviewItem item = ManualReviewItem.pending(500L, Instant.now());
        when(queue.findById(9L)).thenReturn(Optional.of(item));
        stubSummary(500L, 42L);

        service.approve(9L, 7L);

        verify(contentService).approveReview(500L);
        verify(notifications).send(eq(42L), eq(NotificationType.CONTENT_REVIEW_APPROVED), any(), any(), any(), any());
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

        service.reject(10L, 7L);

        verify(contentService).discardReview(501L);
        verify(notifications).send(eq(43L), eq(NotificationType.CONTENT_REVIEW_REJECTED), any(), any(), any(), any());
        assertThat(item.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        verify(auditService).record(eq(7L), eq(AuditActions.CONTENT_REVIEW_REJECTED), eq("CONTENT_POST"),
                eq("501"), any());
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
    void scanTimeoutsNoOpWhenDisabled() {
        when(settingsService.isManualReviewEnabled()).thenReturn(false);

        int n = service.scanTimeouts(Instant.now());

        assertThat(n).isZero();
        verifyNoInteractions(queue);
        verify(contentService, never()).discardReview(anyLong());
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
        verify(notifications).send(eq(44L), eq(NotificationType.CONTENT_REVIEW_REJECTED), any(), any(), any(), any());
        verify(auditService).record(eq(null), eq(AuditActions.CONTENT_REVIEW_TIMED_OUT), eq("CONTENT_POST"),
                eq("600"), any());
    }
}
