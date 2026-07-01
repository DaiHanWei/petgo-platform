package com.tailtopia.admin.audit.repository;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 审计筛选查询的自定义片段（Story 1.3，AC6）。用 Criteria 动态拼条件——<b>仅为非 null 筛选项加谓词</b>，
 * 从根上规避 Postgres「{@code :param IS NULL OR ...} 中无类型 null 参数无法推断类型」的报错
 * （ERROR: could not determine data type of parameter）。保持窄接口：不引入 {@code JpaSpecificationExecutor}
 * （其会附带 {@code delete(Specification)} 改写能力，违反审计 append-only 护栏）。
 */
public interface AdminAuditLogRepositoryCustom {

    /**
     * 按日期范围 [from, to) / 操作人 / 操作类型筛选（任一为 null 即忽略该条件），按 {@code createdAt} 倒序分页。
     */
    Page<AdminAuditLog> search(Instant from, Instant to, Long actor, String action, Pageable pageable);

    /**
     * 取某内容/工单最新一条「主动下架」审计的 summary（Bug 20260701-169，举报队列展示下架原因/摘要）。
     * 优先按内容（{@code CONTENT_POST}/postId，内容管理页下架含「原因：…」文本），回退按工单
     * （{@code CONTENT_REPORT}/reportId，举报队列下架仅「工单X/帖Y」无自由原因）。只读、不改审计链。
     */
    Optional<String> latestTakedownSummary(long postId, long reportId);
}
