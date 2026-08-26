package com.tailtopia.share.repository;

import com.tailtopia.share.domain.ShareRewardQuota;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 月度分享奖励额度仓储（V1.1.6 Story 18.1）。
 *
 * <p>🛡 <b>并发不超发的核心</b>：{@link #tryGrant} 是单行原子条件 UPDATE，自带行锁，
 * 天然串行化同一 {@code (user_id, period)} 的并发发放，{@code WHERE granted + n <= cap}
 * 保证不超发（返回 0 行 = 会超上限）。
 * 🔴 <b>禁应用层读改写</b>（先查再加 = 并发丢更新 = 超发）。
 * 照 {@code UserMonthlyFreeQuotaRepository} / {@code PawCoinWalletRepository} 同一范式。
 */
public interface ShareRewardQuotaRepository extends JpaRepository<ShareRewardQuota, Long> {

    Optional<ShareRewardQuota> findByUserIdAndPeriod(long userId, String period);

    /** 幂等建当月行（并发安全）：靠 {@code uq_share_reward_quotas} 兜并发建。1=新建 / 0=已存在。 */
    @Modifying
    @Query(value = "INSERT INTO share_reward_quotas (user_id, period, granted_coins, "
            + "created_at, updated_at) VALUES (:userId, :period, 0, now(), now()) "
            + "ON CONFLICT (user_id, period) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("period") String period);

    /**
     * 原子占额：{@code granted += coins WHERE granted + coins <= cap}。
     * 返回 1=占到（可以发币）/ 0=会超上限（不发）。
     *
     * <p>⚠️ 条件写的是 {@code granted + coins <= cap} 而不是 {@code granted < cap}：
     * 一次发放可能是 N 枚（渠道层配置），只判「还没满」会让最后一次发放冲过上限 N-1 枚。
     */
    @Modifying
    @Query("update ShareRewardQuota q set q.grantedCoins = q.grantedCoins + :coins, "
            + "q.updatedAt = CURRENT_TIMESTAMP "
            + "where q.userId = :userId and q.period = :period "
            + "and q.grantedCoins + :coins <= :cap")
    int tryGrant(@Param("userId") long userId, @Param("period") String period,
            @Param("coins") long coins, @Param("cap") long cap);
}
