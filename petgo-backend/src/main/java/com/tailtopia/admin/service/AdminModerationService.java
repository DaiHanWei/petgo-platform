package com.tailtopia.admin.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
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
    private final ViolationCountService violationCountService;
    /** 自引用（经代理）：批量逐条调用以让每条走独立事务（避免自调用绕过事务代理）。 */
    private final org.springframework.beans.factory.ObjectProvider<AdminModerationService> selfProvider;

    public AdminModerationService(ReportService reportService, ContentService contentService,
            AdminAuditService auditService, ApplicationEventPublisher events,
            ViolationCountService violationCountService,
            org.springframework.beans.factory.ObjectProvider<AdminModerationService> selfProvider) {
        this.reportService = reportService;
        this.contentService = contentService;
        this.auditService = auditService;
        this.events = events;
        this.violationCountService = violationCountService;
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
                .orElseThrow(() -> AppException.notFound("举报工单不存在").code("admin.err.report.notFound"));
        long handler = handlerId(admin);
        // story 9 幂等（AC-8）：仅当帖当前【未删】时本次下架才是真实迁移 → 计一次；已删再下架为 no-op 不重复计数。
        var summary = contentService.findSummary(r.getPostId());
        Long postAuthorId = summary.map(ContentService.PostSummary::authorId).orElse(null);
        boolean firstTakedown = summary.map(s -> !s.deleted()).orElse(false);
        contentService.softDelete(r.getPostId(), DeleteReason.ADMIN_TAKEDOWN); // 作者通知经既有 ContentRemovedEvent
        // 该帖全部 PENDING 举报单一并 RESOLVED（含本单；P0 多单场景避免残留，bug 20260630-155 同源）。
        reportService.resolvePendingForPost(r.getPostId(), handler);
        auditService.record(admin.getAdminAccountId(), AuditActions.CONTENT_TAKEN_DOWN, "CONTENT_REPORT",
                String.valueOf(reportId), "下架被举报内容（工单 " + reportId + " / 帖 " + r.getPostId() + "）");
        // story 9 §5.1：举报人工判定下架 = 人工判定违规 → 同事务累加 POST 计数（仅真实下架，幂等）。
        if (postAuthorId != null && firstTakedown) {
            violationCountService.record(postAuthorId, ViolationType.POST);
        }
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
                .orElseThrow(() -> AppException.notFound("举报工单不存在").code("admin.err.report.notFound"));
        reportService.mark(reportId, handlerId(admin), ReportStatus.DISMISSED);
        contentService.releaseReportHold(r.getPostId()); // P0 误报恢复；非 P0 held no-op；不发事件/不通知
        auditService.record(admin.getAdminAccountId(), AuditActions.REPORT_DISMISSED, "CONTENT_REPORT",
                String.valueOf(reportId), "驳回举报（工单 " + reportId + "）");
        notifyReporter(r);
        log.info("运营驳回举报 reportId={} adminAccountId={}", reportId, admin.getAdminAccountId());
    }

    /**
     * 按帖驳回（V1.1.4 修复清单 #3）：统一工单队列的内容举报工单<b>按帖聚合</b>，
     * 驳回必须把该帖<b>全部</b> PENDING 举报单一并 DISMISSED —— 沿用单条 {@link #dismiss} 只关一条的话，
     * 剩下的单会把工单钉在 PENDING 上永远关不掉。语义与单条驳回逐项对齐：
     * 每个举报人都收模糊回告；P0 预处置挂起帖恢复可见（非挂起为幂等 no-op）；审计一条（按帖）。
     */
    @Transactional
    public int dismissAllForPost(long postId, AdminUserDetails admin) {
        List<ContentReport> pending = reportService.findPendingForPost(postId);
        if (pending.isEmpty()) {
            throw AppException.notFound("该帖没有待处理的举报");
        }
        long handler = handlerId(admin);
        for (ContentReport r : pending) {
            reportService.mark(r.getId(), handler, ReportStatus.DISMISSED);
            notifyReporter(r);
        }
        contentService.releaseReportHold(postId); // P0 误报恢复；非 P0 held no-op；不发事件/不通知
        auditService.record(admin.getAdminAccountId(), AuditActions.REPORT_DISMISSED, "CONTENT_REPORT",
                String.valueOf(postId), "驳回举报（帖 " + postId + "，共 " + pending.size() + " 单）");
        log.info("运营按帖驳回举报 postId={} count={} adminAccountId={}", postId, pending.size(),
                admin.getAdminAccountId());
        return pending.size();
    }

    /**
     * 按帖下架（统一工单队列批量用）：取该帖任意一条 PENDING 举报单走 {@link #takedown}
     * （它会软删帖子并关掉该帖<b>全部</b> PENDING 单）。没有待处理举报 → 404（工单已被并发处理）。
     */
    @Transactional
    public void takedownByPost(long postId, AdminUserDetails admin) {
        ContentReport first = reportService.findPendingForPost(postId).stream().findFirst()
                .orElseThrow(() -> AppException.notFound("该帖没有待处理的举报"));
        takedown(first.getId(), admin);
    }

    /**
     * 内容举报工单的批量下架/驳回（统一队列 V1.1.4 修复清单 #7：旧 /admin/reports 页下线后
     * 批量能力不能跟着消失）。与账号工单批量同款语义：<b>不开外层事务</b>，逐帖经自引用代理调用
     * （各自独立事务），部分失败不整批回滚，失败逐条回报。
     */
    public BatchResult batchByPost(List<Long> postIds, boolean takedown, AdminUserDetails admin) {
        AdminModerationService self = selfProvider.getObject();
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Long postId : postIds == null ? List.<Long>of() : postIds) {
            try {
                if (takedown) {
                    self.takedownByPost(postId, admin);
                } else {
                    self.dismissAllForPost(postId, admin);
                }
                ok++;
            } catch (RuntimeException e) {
                failed.add("帖 " + postId + "：" + e.getMessage());
            }
        }
        return new BatchResult(ok, failed);
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
