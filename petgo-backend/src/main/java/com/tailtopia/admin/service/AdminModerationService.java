package com.tailtopia.admin.service;

import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin 举报审核编排（Story 3.7，G-1）。经 {@link ReportService}（moderation）读工单 +
 * {@link ContentService}（content）人工下架——**禁直接访问对方 repository**。**所有决定人工**，无自动下架。
 */
@Service
public class AdminModerationService {

    private static final int QUEUE_LIMIT = 50;
    private static final Logger log = LoggerFactory.getLogger(AdminModerationService.class);

    private final ReportService reportService;
    private final ContentService contentService;

    public AdminModerationService(ReportService reportService, ContentService contentService) {
        this.reportService = reportService;
        this.contentService = contentService;
    }

    /** 待处理队列（PENDING，时间倒序），含被举报内容快照 + 该帖举报次数。 */
    @Transactional(readOnly = true)
    public List<ReportQueueItem> pendingQueue() {
        List<ReportQueueItem> items = new ArrayList<>();
        for (ContentReport r : reportService.pendingQueue(QUEUE_LIMIT)) {
            var summary = contentService.findSummary(r.getPostId());
            items.add(new ReportQueueItem(
                    r.getId(),
                    r.getPostId(),
                    r.getReasonType().name(),
                    r.getStatus().name(),
                    r.getCreatedAt(),
                    reportService.countForPost(r.getPostId()),
                    summary.map(ContentService.PostSummary::textPreview).orElse(null),
                    summary.map(ContentService.PostSummary::deleted).orElse(true)));
        }
        return items;
    }

    /** 人工下架：软删内容（经 content service）+ 工单置 RESOLVED。 */
    @Transactional
    public void takedown(long reportId, long adminId) {
        ContentReport r = reportService.find(reportId)
                .orElseThrow(() -> AppException.notFound("举报工单不存在"));
        contentService.softDelete(r.getPostId(), DeleteReason.ADMIN_TAKEDOWN);
        reportService.mark(reportId, adminId, ReportStatus.RESOLVED);
        log.info("运营下架内容 reportId={} postId={} adminId={}", reportId, r.getPostId(), adminId);
    }

    /** 驳回举报：工单置 DISMISSED（内容不动）。 */
    @Transactional
    public void dismiss(long reportId, long adminId) {
        reportService.mark(reportId, adminId, ReportStatus.DISMISSED);
        log.info("运营驳回举报 reportId={} adminId={}", reportId, adminId);
    }
}
