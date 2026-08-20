package com.tailtopia.moderation.repository;

import com.tailtopia.moderation.domain.AccountReport;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 账号举报工单（Story 2.1）。一行 = 一个被举报账号，{@code targetUserId} 唯一。 */
public interface AccountReportRepository extends JpaRepository<AccountReport, Long> {

    Optional<AccountReport> findByTargetUserId(long targetUserId);

    /**
     * 行级写锁版按被举报账号取工单（举报提交路径用，评审三轮 #5）。
     *
     * <p>举报提交与运营处置都<b>先锁同一条工单行再动</b>，把两者串行化：处置进行中提交的新举报
     * 会阻塞到处置事务提交后再读到 {@code RESOLVED}，随后 {@code reopenIfHandled} 正确翻回待处理，
     * 不再静默落入终态工单丢失。两条路径都只锁这<b>一行</b>、加锁顺序一致，无死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AccountReport r WHERE r.targetUserId = :targetUserId")
    Optional<AccountReport> findByTargetUserIdForUpdate(@Param("targetUserId") long targetUserId);

    /** 行级写锁版按工单 id 取（运营处置路径用，与 {@link #findByTargetUserIdForUpdate} 同一行同一把锁）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AccountReport r WHERE r.id = :id")
    Optional<AccountReport> findByIdForUpdate(@Param("id") long id);

    /**
     * 幂等建单：该账号已有工单则什么都不做，返回实际插入行数。
     *
     * <p>⚠️ 必须用 {@code ON CONFLICT DO NOTHING} 而不是「saveAndFlush + catch 唯一约束异常」——
     * 约束异常穿出 repo 代理时共享事务已被标记 rollback-only，catch 内的任何查询都会在
     * 已 abort 的事务里再抛（25P02）→ 并发首报同一账号时败方 500 且整单回滚。
     * 并发下败方的本语句会等胜方提交后落到 DO NOTHING，随后的 find 一定能读到那条工单。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO account_reports (target_user_id, status, first_reported_at, created_at, updated_at)
            VALUES (:targetUserId, 'PENDING', now(), now(), now())
            ON CONFLICT (target_user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@org.springframework.data.repository.query.Param("targetUserId") long targetUserId);
}
