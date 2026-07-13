package com.tailtopia.triage.repository;

import com.tailtopia.triage.domain.UserMonthlyFreeQuota;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 每月免费额度仓储（Story 2.1）。<b>并发不超发核心</b>：{@link #tryConsume} 单行原子条件 UPDATE 自带行锁、
 * 天然串行化同一 {@code (user_id, period)} 的并发扣减，{@code WHERE used_count < :limit} 保证不超发
 * （返回 0 行 = 已达上限）。<b>禁应用层读改写</b>（并发丢更新）。照 {@code PawCoinWalletRepository} 范式。
 */
public interface UserMonthlyFreeQuotaRepository extends JpaRepository<UserMonthlyFreeQuota, Long> {

    Optional<UserMonthlyFreeQuota> findByUserIdAndPeriod(long userId, String period);

    /**
     * 幂等建当月配额行（并发安全）：{@code ON CONFLICT (user_id, period) DO NOTHING} 靠
     * {@code uq_user_monthly_free_quota} 兜并发建，返回 1=新建 / 0=已存在。
     */
    @Modifying
    @Query(value = "INSERT INTO user_monthly_free_quota (user_id, period, used_count, created_at, updated_at) "
            + "VALUES (:userId, :period, 0, now(), now()) ON CONFLICT (user_id, period) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("period") String period);

    /**
     * 原子消耗一次：{@code used_count + 1 WHERE used_count < :limit}。返回受影响行数：1=扣成功 /
     * 0=已达上限（等价 PawCoin 的 {@code balance+delta>=0} 守卫）。行锁串行化并发，不超发。
     */
    @Modifying
    @Query("update UserMonthlyFreeQuota q set q.usedCount = q.usedCount + 1, q.updatedAt = CURRENT_TIMESTAMP "
            + "where q.userId = :userId and q.period = :period and q.usedCount < :limit")
    int tryConsume(@Param("userId") long userId, @Param("period") String period, @Param("limit") int limit);
}
