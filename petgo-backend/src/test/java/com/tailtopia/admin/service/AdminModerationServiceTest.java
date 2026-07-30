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
    private com.tailtopia.moderation.violation.service.ViolationCountService violationCountService;
    private AdminModerationService service;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        contentService = mock(ContentService.class);
        auditService = mock(AdminAuditService.class);
        events = mock(ApplicationEventPublisher.class);
        violationCountService = mock(com.tailtopia.moderation.violation.service.ViolationCountService.class);
        service = new AdminModerationService(reportService, contentService, auditService, events,
                violationCountService, null);
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
        // cm-6：判违规关该帖全部 PENDING 举报单（含本单），不再仅 mark 单条。
        verify(reportService).resolvePendingForPost(50L, 99L);
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
        // cm-6：判误报尝试恢复 P0 预处置挂起（非 P0 held 为幂等 no-op）；不发 ContentPublishedEvent。
        verify(contentService).releaseReportHold(51L);
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
        // PENDING 不查审计原因 → takedownSummary 为空。
        assertThat(items.get(0).getTakedownSummary()).isNull();
    }

    @Test
    void queueResolvedIncludesTakedownSummaryFromAudit() {
        // Bug 20260701-169：已下架队列补下架原因/摘要（来自审计）。
        ContentReport r = report(4L, 60L, 88L);
        when(r.getReasonType()).thenReturn(ReportReason.INAPPROPRIATE);
        when(r.getStatus()).thenReturn(ReportStatus.RESOLVED);
        when(r.getCreatedAt()).thenReturn(Instant.now());
        when(reportService.byStatus(ReportStatus.RESOLVED, 50)).thenReturn(List.of(r));
        when(reportService.countForPost(60L)).thenReturn(1L);
        when(contentService.findSummary(60L)).thenReturn(Optional.of(
                new ContentService.PostSummary(60L, ContentType.DAILY, "preview", true, 77L)));
        when(auditService.takedownSummary(60L, 4L))
                .thenReturn(Optional.of("主动下架内容（原因：Irrelevant content）"));

        List<ReportQueueItem> items = service.queue(ReportStatus.RESOLVED);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTakedownSummary()).isEqualTo("主动下架内容（原因：Irrelevant content）");
    }
}
