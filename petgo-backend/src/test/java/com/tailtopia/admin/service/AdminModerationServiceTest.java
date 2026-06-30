package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.event.ReportResolvedEvent;
import com.tailtopia.moderation.service.ReportService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** L0：举报处理（Story 4.1）——下架/驳回审计 + 举报人模糊通知事件 + queue 组装 reporterId/authorId。 */
class AdminModerationServiceTest {

    private ReportService reportService;
    private ContentService contentService;
    private AdminAuditService auditService;
    private ApplicationEventPublisher events;
    private AdminModerationService service;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        contentService = mock(ContentService.class);
        auditService = mock(AdminAuditService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new AdminModerationService(reportService, contentService, auditService, events, null);
    }

    private AdminUserDetails admin() {
        return new AdminUserDetails(7L, 99L, "ops@x", "{bcrypt}x", AdminAccountType.SUPER_ADMIN);
    }

    private ContentReport report(long id, long postId, Long reporterId) {
        ContentReport r = mock(ContentReport.class);
        when(r.getId()).thenReturn(id);
        when(r.getPostId()).thenReturn(postId);
        when(r.getReporterId()).thenReturn(reporterId);
        return r;
    }

    @Test
    void takedownSoftDeletesMarksAuditsAndNotifiesReporter() {
        ContentReport r = report(1L, 50L, 88L);
        when(reportService.find(1L)).thenReturn(Optional.of(r));

        service.takedown(1L, admin());

        verify(contentService).softDelete(50L, DeleteReason.ADMIN_TAKEDOWN);
        verify(reportService).mark(1L, 99L, ReportStatus.RESOLVED);
        verify(auditService).record(eq(7L), eq(AuditActions.CONTENT_TAKEN_DOWN), eq("CONTENT_REPORT"),
                eq("1"), any());
        verify(events).publishEvent(any(ReportResolvedEvent.class));
    }

    @Test
    void dismissMarksAuditsAndNotifiesReporter() {
        ContentReport r = report(2L, 51L, 88L);
        when(reportService.find(2L)).thenReturn(Optional.of(r));

        service.dismiss(2L, admin());

        verify(reportService).mark(2L, 99L, ReportStatus.DISMISSED);
        verify(auditService).record(eq(7L), eq(AuditActions.REPORT_DISMISSED), eq("CONTENT_REPORT"),
                eq("2"), any());
        verify(events).publishEvent(any(ReportResolvedEvent.class));
    }

    @Test
    void queueAssemblesReporterAndAuthor() {
        ContentReport r = report(3L, 52L, 88L);
        when(r.getReasonType()).thenReturn(ReportReason.INAPPROPRIATE);
        when(r.getStatus()).thenReturn(ReportStatus.PENDING);
        when(r.getCreatedAt()).thenReturn(Instant.now());
        when(reportService.byStatus(ReportStatus.PENDING, 50)).thenReturn(List.of(r));
        when(reportService.countForPost(52L)).thenReturn(2L);
        when(contentService.findSummary(52L)).thenReturn(Optional.of(
                new ContentService.PostSummary(52L, ContentType.DAILY, "preview", false, 77L)));

        List<ReportQueueItem> items = service.queue(ReportStatus.PENDING);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getReporterId()).isEqualTo(88L);
        assertThat(items.get(0).getAuthorId()).isEqualTo(77L);
        assertThat(items.get(0).getReportCount()).isEqualTo(2L);
    }
}
