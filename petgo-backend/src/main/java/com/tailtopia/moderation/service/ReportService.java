package com.tailtopia.moderation.service;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.repository.ContentReportRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容举报工单（Story 3.7，FR-25）。用户提交（**无自动下架**）+ Admin 队列读取/处理。
 *
 * <p>模块边界：内容存在性校验经 {@link ContentService}（**不直读 content 表**）；下架由 admin 层经
 * content service 完成。V1 仅内容举报。
 *
 * <p>F10 范围对齐（AC3 ④）：关键词过滤 + 三方图像识别已<b>前移到发布时</b>由三方系统执行
 * （FR-12，Story 2.3 {@code ContentService.publish}）；本举报模块<b>仅处理已发布内容</b>的用户举报与
 * 运营人工下架，<b>不含</b>发布时自动过滤逻辑，亦无任何规则自动下架——所有下架决定人工经 Admin。
 * 举报人侧仅收到提交反馈，<b>审核结果不回告</b>（V1 无举报进度查询、无申诉入口）。
 */
@Service
public class ReportService {

    /**
     * P0 阈值（内容审核 cm-6 §5.1）：同一内容去重唯一举报用户数 ≥ 此值即 P0 自动预处置。
     * 计数天然 = 唯一用户数（唯一约束 + 举报即隐藏，D-CM7），无需额外 DISTINCT。
     */
    static final long P0_REPORT_COUNT_THRESHOLD = 10;

    private final ContentReportRepository reports;
    private final ContentService contentService;

    public ReportService(ContentReportRepository reports, ContentService contentService) {
        this.reports = reports;
        this.contentService = contentService;
    }

    /**
     * 提交举报。重复举报（同 reporter 同 post）幂等。
     *
     * <p>写工单本身即完成「对举报者隐藏」的数据落地（隐藏判据=该 reporter 对该 post 存在举报记录，见 §4.1/§5.3；
     * 读路径过滤在 {@code findFeed} / 详情）——零新增写。
     *
     * <p>写入后**同事务同步**算 P0 阈值并按需自动预处置（cm-6 §5.2，R3：QPS 极低、O(1)、需强一致，禁 @Async/MQ）。
     * P1/P2 不改内容可见性（仅工单累积，按计数派生优先级由后台读时映射）。
     */
    @Transactional
    public void submit(long postId, long reporterId, ReportReason reason) {
        if (!contentService.isVisible(postId)) {
            throw AppException.notFound("内容不存在");
        }
        if (reports.existsByPostIdAndReporterId(postId, reporterId)) {
            return; // 幂等：已举报过（隐藏已成立，计数/预处置状态不变）
        }
        try {
            reports.save(ContentReport.create(postId, reporterId, reason));
        } catch (DataIntegrityViolationException e) {
            return; // 并发撞唯一约束：本次未新增，预处置交由胜出线程处理，幂等吞掉。
        }
        maybeApplyP0PreDisposal(postId, reason);
    }

    /**
     * P0 阈值判定 + 自动预处置（cm-6 §5.2）。P0 = 含 {@code ILLEGAL} 原因（CM4：单次即触发，R2 取「是」）
     * 或去重唯一举报用户数 ≥ {@link #P0_REPORT_COUNT_THRESHOLD}。命中则把**已发布**帖翻回挂起
     * （{@link ContentService#applyReportHoldIfPublished}，幂等 + 不通知）。P1/P2 不在此触发任何动作。
     *
     * <p><b>不含</b>「三方高风险 ≥0.8」路径——该情形已由 cm-2 提交时（FR-12A）拦截，帖子从未公开发布（§5.1）。
     */
    private void maybeApplyP0PreDisposal(long postId, ReportReason justReported) {
        boolean legalReport = justReported == ReportReason.ILLEGAL
                || reports.existsByPostIdAndReasonType(postId, ReportReason.ILLEGAL);
        long count = reports.countByPostId(postId); // 唯一约束 + 举报即隐藏 → 天然 = 唯一用户数（D-CM7）
        if (legalReport || count >= P0_REPORT_COUNT_THRESHOLD) {
            contentService.applyReportHoldIfPublished(postId);
        }
    }

    /**
     * 该用户是否已举报该帖（内容审核 cm-6 §5.4）：供内容详情读路径判「对举报者视同不可见 → 404」。
     * 经 service 暴露给 content 侧，避免 content 直读 moderation repo（模块边界）。
     */
    @Transactional(readOnly = true)
    public boolean hasReported(long postId, long reporterId) {
        return reports.existsByPostIdAndReporterId(postId, reporterId);
    }

    @Transactional(readOnly = true)
    public List<ContentReport> pendingQueue(int limit) {
        return reports.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING, PageRequest.of(0, limit));
    }

    /** Story 4.1：按状态列工单（PENDING/RESOLVED/DISMISSED），时间倒序。 */
    @Transactional(readOnly = true)
    public List<ContentReport> byStatus(ReportStatus status, int limit) {
        return reports.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public Optional<ContentReport> find(long reportId) {
        return reports.findById(reportId);
    }

    @Transactional(readOnly = true)
    public long countForPost(long postId) {
        return reports.countByPostId(postId);
    }

    /** 标记工单处理结果（RESOLVED 下架 / DISMISSED 驳回），记处理人。 */
    @Transactional
    public void mark(long reportId, long adminId, ReportStatus decision) {
        ContentReport r = reports.findById(reportId)
                .orElseThrow(() -> AppException.notFound("举报工单不存在"));
        r.resolveBy(adminId, decision);
        reports.save(r);
    }

    /**
     * 内容被下架时，把该帖所有 PENDING 举报单一并置 RESOLVED，避免已下架内容仍残留在待处理队列
     * （bug 20260630-155；同时解决同帖多举报单只关一条的隐患）。返回本次处理条数。
     */
    @Transactional
    public int resolvePendingForPost(long postId, long adminId) {
        List<ContentReport> pending = reports.findByPostIdAndStatus(postId, ReportStatus.PENDING);
        for (ContentReport r : pending) {
            r.resolveBy(adminId, ReportStatus.RESOLVED);
        }
        reports.saveAll(pending);
        return pending.size();
    }
}
