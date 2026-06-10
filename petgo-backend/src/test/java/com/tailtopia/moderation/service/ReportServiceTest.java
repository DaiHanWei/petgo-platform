package com.tailtopia.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.repository.ContentReportRepository;
import com.tailtopia.shared.error.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：举报提交（无自动下架）+ 重复幂等 + 不存在内容 404 + 工单状态流转（AC1/AC2 逻辑面）。 */
class ReportServiceTest {

    private ContentReportRepository reports;
    private ContentService contentService;
    private ReportService service;

    @BeforeEach
    void setUp() {
        reports = mock(ContentReportRepository.class);
        contentService = mock(ContentService.class);
        service = new ReportService(reports, contentService);
        when(contentService.isVisible(1L)).thenReturn(true);
    }

    @Test
    void submitWritesPendingTicketNoAutoTakedown() {
        when(reports.existsByPostIdAndReporterId(1L, 9L)).thenReturn(false);
        service.submit(1L, 9L, ReportReason.HARASSMENT);
        ArgumentCaptor<ContentReport> cap = ArgumentCaptor.forClass(ContentReport.class);
        verify(reports).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(cap.getValue().getReasonType()).isEqualTo(ReportReason.HARASSMENT);
        // 无下架：ReportService 不碰 contentService.softDelete（仅 isVisible 校验）。
        verify(contentService, never()).softDelete(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void duplicateReportIsIdempotent() {
        when(reports.existsByPostIdAndReporterId(1L, 9L)).thenReturn(true);
        service.submit(1L, 9L, ReportReason.OTHER);
        verify(reports, never()).save(any());
    }

    @Test
    void reportOnMissingContentIsNotFound() {
        when(contentService.isVisible(404L)).thenReturn(false);
        assertThatThrownBy(() -> service.submit(404L, 9L, ReportReason.ILLEGAL))
                .isInstanceOf(AppException.class);
        verify(reports, never()).save(any());
    }

    @Test
    void markUpdatesStatusAndHandler() {
        ContentReport r = ContentReport.create(1L, 9L, ReportReason.MISINFO);
        when(reports.findById(5L)).thenReturn(java.util.Optional.of(r));
        service.mark(5L, 100L, ReportStatus.RESOLVED);
        assertThat(r.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(r.getHandledBy()).isEqualTo(100L);
        verify(reports).save(r);
    }
}
