package com.tailtopia.moderation.throttle.repository;

import com.tailtopia.moderation.throttle.domain.RankThrottle;
import com.tailtopia.moderation.throttle.domain.ThrottleScope;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RankThrottleRepository extends JpaRepository<RankThrottle, Long> {

    /**
     * 打分链路的取数：一次取回可能命中的全部限流行，生效判定交给
     * {@link RankThrottle#isActiveAt(java.time.Instant)}。
     *
     * <p>🔴 <b>批量、一次查询</b>。逐条查会让推荐序的取数次数随候选池线性增长 ——
     * 这正是 16 系列反复强调「禁止逐条 COUNT」的同一个坑。
     *
     * <p>⚠️ 生效条件（未解除 且 未到期）刻意<b>不写进 SQL</b>：那三个条件的组合
     * 只在 {@code isActiveAt} 一处实现，SQL 里再写一遍就有了第二处，
     * 而两处漂了不会报错（典型表现是手动解除在推荐序里不生效）。
     * 本表是治理动作、量级几十到几百行，多取几行已解除的没有代价。
     */
    @Query("""
            select t from RankThrottle t
             where (t.scope = :postScope and t.targetId in :postIds)
                or (t.scope = :accountScope and t.targetId in :authorIds)
            """)
    List<RankThrottle> findCandidates(@Param("postScope") ThrottleScope postScope,
            @Param("postIds") Collection<Long> postIds,
            @Param("accountScope") ThrottleScope accountScope,
            @Param("authorIds") Collection<Long> authorIds);

    /** 后台列表用（17-2）：按目标查全部历史，最近的在前。 */
    List<RankThrottle> findByScopeAndTargetIdOrderByCreatedAtDesc(ThrottleScope scope,
            long targetId);
}
