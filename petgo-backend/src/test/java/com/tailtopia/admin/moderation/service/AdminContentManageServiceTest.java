package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：内容管理 service（Story 4.2）——下架必填原因、下架/恢复委托 + 审计强制副作用 + 违规计数（story 9，幂等）。 */
class AdminContentManageServiceTest {

    private ContentService contentService;
    private AdminAuditService auditService;
    private ReportService reportService;
    private ViolationCountService violationCountService;
    private AdminContentManageService service;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        auditService = mock(AdminAuditService.class);
        reportService = mock(ReportService.class);
        violationCountService = mock(ViolationCountService.class);
        service = new AdminContentManageService(contentService, auditService, reportService,
                violationCountService);
    }

    private void stubSummary(long postId, long authorId, boolean deleted) {
        when(contentService.findSummary(postId)).thenReturn(Optional.of(
                new ContentService.PostSummary(postId, ContentType.DAILY, "x", deleted, authorId)));
    }

    @Test
    void browseDelegatesToContentService() {
        service.browse("DAILY", 7L, null, null, "ONLINE", "猫", 0);
        verify(contentService).adminSearch(eq(ContentType.DAILY), eq(7L), any(), any(),
                eq(Boolean.FALSE), eq("猫"), anyInt(), anyInt());
    }

    @Test
    void takedownRejectsBlankReason() {
        assertThatThrownBy(() -> service.takedown(5L, "  ", 1L)).isInstanceOf(AppException.class);
        verifyNoInteractions(contentService);
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void takedownSoftDeletesAndAudits() {
        service.takedown(5L, "垃圾广告", 1L);
        verify(contentService).softDelete(5L, DeleteReason.ADMIN_TAKEDOWN);
        // bug 20260630-155：下架同时关闭该帖 PENDING 举报单。
        verify(reportService).resolvePendingForPost(5L, 1L);
        verify(auditService).record(eq(1L), eq(AuditActions.CONTENT_TAKEN_DOWN), eq("CONTENT_POST"),
                eq("5"), contains("垃圾广告"));
    }

    @Test
    void takedownOfLivePostRecordsPostViolationOnce() {
        stubSummary(5L, 42L, false); // 未删的活帖 → 真实下架
        service.takedown(5L, "垃圾广告", 1L);
        // story 9 §5.1：巡查下架 = 人工判定违规 → 累加作者 POST 计数一次。
        verify(violationCountService).record(42L, ViolationType.POST);
    }

    @Test
    void takedownOfAlreadyDeletedDoesNotRecord() {
        stubSummary(5L, 42L, true); // 已删帖再下架 = no-op → 不重复计数（AC-8 幂等）。
        service.takedown(5L, "垃圾广告", 1L);
        verify(violationCountService, never()).record(anyLong(), any());
    }

    @Test
    void restoreDelegatesAndAudits() {
        assertThatCode(() -> service.restore(9L, 2L)).doesNotThrowAnyException();
        verify(contentService).restore(9L);
        verify(auditService).record(eq(2L), eq(AuditActions.CONTENT_RESTORED), eq("CONTENT_POST"),
                eq("9"), any());
    }
}
