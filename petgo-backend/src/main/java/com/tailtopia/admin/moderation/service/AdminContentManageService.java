package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台全量内容管理（Story 4.2，AB-3B）。**经 {@link ContentService}** 浏览/主动下架/恢复，禁 admin 直读 content repo。
 * 下架/恢复同事务写审计；下架必填原因（进审计 summary，不进作者通知）。安全攸关：勿埋绕过点。
 */
@Service
public class AdminContentManageService {

    private static final int PAGE_SIZE = 50;

    private final ContentService contentService;
    private final AdminAuditService auditService;
    private final ReportService reportService;
    private final ViolationCountService violationCountService;

    public AdminContentManageService(ContentService contentService, AdminAuditService auditService,
            ReportService reportService, ViolationCountService violationCountService) {
        this.contentService = contentService;
        this.auditService = auditService;
        this.reportService = reportService;
        this.violationCountService = violationCountService;
    }

    /** 全量浏览/筛选/搜索。status: ONLINE / DELETED / null=全部；type/authorId/q 任一空忽略。 */
    @Transactional(readOnly = true)
    public List<AdminContentRow> browse(String type, Long authorId, LocalDate from, LocalDate to,
            String status, String q, int page) {
        ContentType ct = parseType(type);
        Boolean deleted = "DELETED".equals(status) ? Boolean.TRUE
                : ("ONLINE".equals(status) ? Boolean.FALSE : null);
        Instant fromI = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String keyword = (q == null || q.isBlank()) ? null : q;
        return contentService.adminSearch(ct, authorId, fromI, toI, deleted, keyword,
                PAGE_SIZE, Math.max(page, 0) * PAGE_SIZE);
    }

    /** 按 id 取单条后台行（HTMX 局部刷新用）；不存在返回 null。 */
    @Transactional(readOnly = true)
    public AdminContentRow row(long postId) {
        return contentService.adminRow(postId);
    }

    /** 主动下架（必填原因）：软删 + 关闭该帖待处理举报单 + 作者通知（既有事件）+ 审计。 */
    @Transactional
    public void takedown(long postId, String reason, long actorAccountId) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("下架原因不能为空");
        }
        // story 9 幂等（AC-8）：仅当帖当前【未删】时本次下架才是真实迁移 → 计一次。
        var summary = contentService.findSummary(postId);
        Long postAuthorId = summary.map(ContentService.PostSummary::authorId).orElse(null);
        boolean firstTakedown = summary.map(s -> !s.deleted()).orElse(false);
        contentService.softDelete(postId, DeleteReason.ADMIN_TAKEDOWN);
        // bug 20260630-155：内容管理主动下架时同步关闭该帖 PENDING 举报单，避免残留在举报待处理队列。
        reportService.resolvePendingForPost(postId, actorAccountId);
        auditService.record(actorAccountId, AuditActions.CONTENT_TAKEN_DOWN, "CONTENT_POST",
                String.valueOf(postId), "主动下架内容（原因：" + reason.trim() + "）");
        // story 9 §5.1：后台巡查下架 = 人工判定违规 → 同事务累加 POST 计数（仅真实下架，幂等）。
        if (postAuthorId != null && firstTakedown) {
            violationCountService.record(postAuthorId, ViolationType.POST);
        }
    }

    /** 恢复已下架内容 + 审计。 */
    @Transactional
    public void restore(long postId, long actorAccountId) {
        contentService.restore(postId);
        auditService.record(actorAccountId, AuditActions.CONTENT_RESTORED, "CONTENT_POST",
                String.valueOf(postId), "恢复已下架内容");
    }

    private ContentType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return ContentType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
