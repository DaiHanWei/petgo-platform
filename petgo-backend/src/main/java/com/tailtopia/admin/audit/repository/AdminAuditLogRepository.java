package com.tailtopia.admin.audit.repository;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * 审计日志仓库（Story 1.3，A6）。**窄接口**：仅继承基础 {@link Repository}（非 {@code JpaRepository}），
 * 只声明 append（{@code save}）+ 查询——<b>不暴露任何 delete/批量改写</b>，应用层无从篡改；
 * DB 触发器（V32）是最后防线。筛选分页由 {@link AdminAuditLogRepositoryCustom} 用 Criteria 动态拼接
 * （规避无类型 null 参数报错，且不引入会带 delete 的 {@code JpaSpecificationExecutor}）。
 * 链尾查询不加悲观行锁：并发串行化由 {@code AdminAuditService} 内的 Postgres advisory 事务锁统一保证（AG-2），
 * 避免 {@code LIMIT + FOR UPDATE} 在 READ COMMITTED 下的链分叉。
 */
public interface AdminAuditLogRepository
        extends Repository<AdminAuditLog, Long>, AdminAuditLogRepositoryCustom {

    /** append 一行（唯一写入口）。 */
    AdminAuditLog save(AdminAuditLog log);

    /** 链尾（最新一行），用于取 {@code prevHash}。 */
    Optional<AdminAuditLog> findTopByOrderByIdDesc();

    /** 全量升序（运维/测试逐行复算校验链完整性用）。 */
    List<AdminAuditLog> findAllByOrderByIdAsc();
}
