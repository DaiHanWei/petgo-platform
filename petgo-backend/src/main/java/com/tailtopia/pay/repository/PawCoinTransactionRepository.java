package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.PawCoinTransaction;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PawCoin 流水仓储（Story 1.2）。{@link #findByUserIdOrderByCreatedAtDesc} 供余额/流水页（1.4）游标分页读。
 */
public interface PawCoinTransactionRepository extends JpaRepository<PawCoinTransaction, Long> {

    List<PawCoinTransaction> findByUserIdOrderByCreatedAtDesc(long userId, Pageable pageable);

    /**
     * 游标分页：某用户在游标 {@code (beforeTs, beforeId)} 之前的流水（供 1.4 加载更多）。
     *
     * <p>🔴🔴 <b>游标必须是 {@code (created_at, id)} 复合的</b>
     * （2026-08-18 修，{@code action_items: NOTIFY-CURSOR-TIE} 同一族）。
     *
     * <p>钱包流水是这一族里<b>最容易撞上</b>的：一次结算就在同一个事务里写多条
     * （抵扣 + 退款分账 + 补偿），而 Postgres 的 {@code now()} 是<b>事务开始时刻</b> ——
     * 这几条的 {@code created_at} <b>一模一样</b>。原来的 {@code created_at < cursor}
     * 会把整个同刻组一次跳过，用户在流水里<b>永久</b>看不到那几笔。
     * 🔴 这是钱的账，少一笔就是对不上。
     */
    @Query("""
            SELECT t FROM PawCoinTransaction t
            WHERE t.userId = :userId
              AND (t.createdAt < :beforeTs
                   OR (t.createdAt = :beforeTs AND t.id < :beforeId))
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<PawCoinTransaction> findPageBefore(@Param("userId") long userId,
            @Param("beforeTs") Instant beforeTs, @Param("beforeId") long beforeId,
            Pageable pageable);

    /**
     * 注销级联（Story 1.6）：物理删该用户全部流水。返回删除行数（0=无流水，幂等）。
     * 个人流水随注销删除；对账留痕在 append-only 的 {@code ledger_entries}（不删）。
     */
    @Modifying
    @Query("delete from PawCoinTransaction t where t.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
