package com.tailtopia.admin.audit.repository;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import java.time.Instant;
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
}
