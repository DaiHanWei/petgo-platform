package com.petgo.moderation.service;

import com.petgo.content.service.ContentService;
import com.petgo.moderation.domain.ContentReport;
import com.petgo.moderation.domain.ReportReason;
import com.petgo.moderation.domain.ReportStatus;
import com.petgo.moderation.repository.ContentReportRepository;
import com.petgo.shared.error.AppException;
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

    private final ContentReportRepository reports;
    private final ContentService contentService;

    public ReportService(ContentReportRepository reports, ContentService contentService) {
        this.reports = reports;
        this.contentService = contentService;
    }

    /** 提交举报。重复举报（同 reporter 同 post）幂等；**不触发任何自动下架**。 */
    @Transactional
    public void submit(long postId, long reporterId, ReportReason reason) {
        if (!contentService.isVisible(postId)) {
            throw AppException.notFound("内容不存在");
        }
        if (reports.existsByPostIdAndReporterId(postId, reporterId)) {
            return; // 幂等：已举报过
        }
        try {
            reports.save(ContentReport.create(postId, reporterId, reason));
        } catch (DataIntegrityViolationException e) {
            // 并发撞唯一约束：幂等吞掉。
        }
    }

    @Transactional(readOnly = true)
    public List<ContentReport> pendingQueue(int limit) {
        return reports.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING, PageRequest.of(0, limit));
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
}
