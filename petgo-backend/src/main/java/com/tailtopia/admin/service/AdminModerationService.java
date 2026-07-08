package com.tailtopia.admin.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.event.ReportResolvedEvent;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin 举报审核编排（Story 3.7 基线 + 4.1 批量/双向通知/审计/状态筛选）。经 {@link ReportService}（moderation）
 * 读工单 + {@link ContentService}（content）人工下架——**禁直接访问对方 repository**。所有决定人工，无自动下架。
 *
 * <p>4.1 关键行为：每条下架/驳回（含批量逐条）①经 {@link AdminAuditService} 写审计 ②发 {@link ReportResolvedEvent}
 * → notify 向举报人发**统一模糊通知**（不透露结果/内容/作者）。作者下架通知复用既有 {@code ContentRemovedEvent}（零改）。
 */
@Service
public class AdminModerationService {

    private static final int QUEUE_LIMIT = 50;
    private static final Logger log = LoggerFactory.getLogger(AdminModerationService.class);

    private final ReportService reportService;
    private final ContentService contentService;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;
    /** 自引用（经代理）：批量逐条调用以让每条走独立事务（避免自调用绕过事务代理）。 */
    private final org.springframework.beans.factory.ObjectProvider<AdminModerationService> selfProvider;

    public AdminModerationService(ReportService reportService, ContentService contentService,
            AdminAuditService auditService, ApplicationEventPublisher events,
            org.springframework.beans.factory.ObjectProvider<AdminModerationService> selfProvider) {
        this.reportService = reportService;
        this.contentService = contentService;
        this.auditService = auditService;
        this.events = events;
        this.selfProvider = selfProvider;
    }

    /** 待处理队列（PENDING，时间倒序）。 */
    @Transactional(readOnly = true)
    public List<ReportQueueItem> pendingQueue() {
        return queue(ReportStatus.PENDING);
    }

    /** 按状态队列（Story 4.1：PENDING / RESOLVED / DISMISSED），时间倒序。 */
    @Transactional(readOnly = true)
    public List<ReportQueueItem> queue(ReportStatus status) {
        List<ReportQueueItem> items = new ArrayList<>();
        for (ContentReport r : reportService.byStatus(status, QUEUE_LIMIT)) {
            var summary = contentService.findSummary(r.getPostId());
            // Bug 169：仅「已下架(RESOLVED)」工单补下架原因/摘要（查审计），其它态不查。
            String takedownSummary = status == ReportStatus.RESOLVED
                    ? auditService.takedownSummary(r.getPostId(), r.getId()).orElse(null)
                    : null;
            items.add(new ReportQueueItem(
                    r.getId(),
                    r.getPostId(),
                    r.getReporterId(),
                    summary.map(ContentService.PostSummary::authorId).orElse(null),
                    r.getReasonType().name(),
                    r.getStatus().name(),
                    r.getCreatedAt(),
                    reportService.countForPost(r.getPostId()),
                    summary.map(ContentService.PostSummary::textPreview).orElse(null),
                    summary.map(ContentService.PostSummary::deleted).orElse(true),
                    r.getHandledBy(),
                    r.getHandledAt(),
                    takedownSummary));
        }
        return items;
    }

    /**
     * 人工下架（判违规，单条入口）：软删内容 + **关闭该帖全部 PENDING 举报单**（{@code resolvePendingForPost}）
     * + 审计 + 举报人模糊通知。作者「内容已被隐藏」通知复用既有 {@link ContentService#softDelete} 的
     * {@code ContentRemovedEvent}（CONTENT_REMOVED，§8.9）。
     *
     * <p>内容审核 cm-6：P0 挂起帖（可能多条 PENDING）判违规走此路径——软删（内容不再对外）+ 全单结单
     * （避免残留在队列，AC-B8）。挂起态帖软删同样生效（softDelete 仅看 deletedAt）。
     */
    @Transactional
    public void takedown(long reportId, AdminUserDetails admin) {
        ContentReport r = reportService.find(reportId)
                .orElseThrow(() -> AppException.notFound("举报工单不存在"));
        long handler = handlerId(admin);
        contentService.softDelete(r.getPostId(), DeleteReason.ADMIN_TAKEDOWN); // 作者通知经既有 ContentRemovedEvent
        // 该帖全部 PENDING 举报单一并 RESOLVED（含本单；P0 多单场景避免残留，bug 20260630-155 同源）。
        reportService.resolvePendingForPost(r.getPostId(), handler);
        auditService.record(admin.getAdminAccountId(), AuditActions.CONTENT_TAKEN_DOWN, "CONTENT_REPORT",
                String.valueOf(reportId), "下架被举报内容（工单 " + reportId + " / 帖 " + r.getPostId() + "）");
        notifyReporter(r);
        log.info("运营下架内容 reportId={} postId={} adminAccountId={}", reportId, r.getPostId(),
                admin.getAdminAccountId());
    }

    /**
     * 驳回举报（判误报，单条入口）：工单 DISMISSED + 审计 + 举报人模糊通知。
     *
     * <p>内容审核 cm-6（§5.2 判误报 · D-CM6）：若该帖处于举报驱动 P0 预处置挂起
     * （{@code reportHiddenAt} 非空）→ {@link ContentService#releaseReportHold} 恢复对外可见
     * （UNDER_REVIEW → PUBLISHED + 清 reportHiddenAt）；**恢复不发 ContentPublishedEvent、不通知作者**
     * （避免里程碑等副作用双 fire）。非 P0 挂起帖此调用为幂等 no-op（P1/P2 从未改可见性）。
     * 原举报者仍对其隐藏（举报记录不撤，R5）。
     */
    @Transactional
    public void dismiss(long reportId, AdminUserDetails admin) {
        ContentReport r = reportService.find(reportId)
                .orElseThrow(() -> AppException.notFound("举报工单不存在"));
        reportService.mark(reportId, handlerId(admin), ReportStatus.DISMISSED);
        contentService.releaseReportHold(r.getPostId()); // P0 误报恢复；非 P0 held no-op；不发事件/不通知
        auditService.record(admin.getAdminAccountId(), AuditActions.REPORT_DISMISSED, "CONTENT_REPORT",
                String.valueOf(reportId), "驳回举报（工单 " + reportId + "）");
        notifyReporter(r);
        log.info("运营驳回举报 reportId={} adminAccountId={}", reportId, admin.getAdminAccountId());
    }

    /**
     * 批量下架/驳回（Story 4.1 AC5）：**不开外层事务**，逐条经自引用代理调用（各自独立事务）；
     * 部分失败不影响已成功项，汇总结果回报。
     */
    public BatchResult batch(List<Long> reportIds, boolean takedown, AdminUserDetails admin) {
        AdminModerationService self = selfProvider.getObject();
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Long id : reportIds == null ? List.<Long>of() : reportIds) {
            try {
                if (takedown) {
                    self.takedown(id, admin);
                } else {
                    self.dismiss(id, admin);
                }
                ok++;
            } catch (RuntimeException e) {
                failed.add("工单 " + id + "：" + e.getMessage());
            }
        }
        return new BatchResult(ok, failed);
    }

    private long handlerId(AdminUserDetails admin) {
        // 处理人标识（content_report.handled_by 无 FK）：优先官方内容作者 users.id，缺则用后台账号 id。
        return admin.hasOperatorUserId() ? admin.getUserId() : admin.getAdminAccountId();
    }

    private void notifyReporter(ContentReport r) {
        if (r.getReporterId() != null) {
            events.publishEvent(new ReportResolvedEvent(r.getId(), r.getReporterId(), Instant.now()));
        }
    }

    /** 批量处理汇总结果。 */
    public record BatchResult(int ok, List<String> failed) {
        public int failedCount() {
            return failed.size();
        }
    }
}
