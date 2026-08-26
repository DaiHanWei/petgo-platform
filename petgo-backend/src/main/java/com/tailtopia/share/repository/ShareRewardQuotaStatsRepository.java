package com.tailtopia.share.repository;

import com.tailtopia.share.domain.ShareRewardQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 分享奖励额度的**后台统计**查询（V1.1.6 Story 18.3 · AC3）。
 *
 * <p>与 {@link ShareRewardQuotaRepository} 分开，是为了让写路径那个仓储保持极小 ——
 * 它承载「并发不超发」这件事，多几个统计方法会让人不确定哪个是热路径。
 */
public interface ShareRewardQuotaStatsRepository extends JpaRepository<ShareRewardQuota, Long> {

    /** 某 period 的已发放总量。无数据返回 0（COALESCE，不返回 null）。 */
    @Query("select coalesce(sum(q.grantedCoins), 0) from ShareRewardQuota q where q.period = :period")
    long sumGranted(@Param("period") String period);

    /**
     * 某 period 达到上限的账号数。
     *
     * <p>⚠️ 判据是 {@code granted >= cap} 而不是 {@code = cap}：
     * 上限被下调之后，此前已发到旧上限的账号仍然算"达上限"——
     * 而它们确实拿不到更多了，运营看这个数就是为了知道"多少人已经封顶"。
     */
    @Query("select count(q) from ShareRewardQuota q "
            + "where q.period = :period and q.grantedCoins >= :cap")
    long countAtCap(@Param("period") String period, @Param("cap") long cap);
}
