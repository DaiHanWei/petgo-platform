package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.UserTagAssignment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用户标签分配仓储（V1.1.6 Story 5.1）。
 *
 * <h2>🛡 只有批量方法，刻意不提供"按单个用户取"</h2>
 * AD-11：四处展示位一律批量，**评论区尤其禁止逐条查**（一页评论可达数十条）。
 * 这里不给逐条方法，是为了让"逐条查"连写都写不出来 —— 单个用户就传一个只有一个元素的集合。
 *
 * <h2>时间窗口径与 {@code ScheduleWindow} 必须一致</h2>
 * 生效中 = {@code startsAt <= now AND (endsAt IS NULL OR now < endsAt)}。
 * <b>左闭右开</b>，且 <b>endsAt 为空 = 永久</b>。两侧一致性由
 * {@code UserTagIntegrationTest} 用一组边界时刻逐一对照来堵（同 Story 4.1 的做法）。
 */
public interface UserTagAssignmentRepository extends JpaRepository<UserTagAssignment, Long> {

    /**
     * 一批用户当前生效中的标签分配（含标签配置），按**分配时间倒序**。
     *
     * <p>倒序是为了取"最近 3 个"—— 排序放在 SQL 里，取用方只管截断。
     */
    @Query("""
            select a, t from UserTagAssignment a, UserTag t
             where a.tagId = t.id
               and a.userId in :userIds
               and a.startsAt <= :now
               and (a.endsAt is null or :now < a.endsAt)
             order by a.startsAt desc, a.id desc
            """)
    List<Object[]> findActiveWithTag(@Param("userIds") Collection<Long> userIds,
            @Param("now") Instant now);

    /**
     * 后台「按标签」维度（Story 11.3）：某标签当前**生效中**的分配。
     *
     * <p>口径与 {@link #findActiveWithTag} 一致（半开区间，{@code endsAt} 为空 = 永久）。
     * 🛡 无状态列、无扫描器。
     */
    @Query("""
            select a from UserTagAssignment a
             where a.tagId = :tagId
               and a.startsAt <= :now
               and (a.endsAt is null or :now < a.endsAt)
             order by a.startsAt desc, a.id desc
            """)
    List<UserTagAssignment> findActiveByTag(@Param("tagId") long tagId, @Param("now") Instant now);

    /** 后台「按用户」维度：某用户全部分配（含已失效的历史）。 */
    List<UserTagAssignment> findByUserIdOrderByStartsAtDesc(long userId);

    /**
     * 后台抽屉页签二（V1.3.0 Story 8.2 · AC3）：某标签的分配记录，**含已到期**，分页。
     *
     * <p>⚠️ 与 {@link #findActiveByTag} 的差别就是「不只看生效中的」——
     * AC3 要的正是「生效中 / 待生效 / 已到期」并列：只列生效中的话，
     * 运营看不出「上周那次分配到底到期了没」，只会看到它凭空消失。
     */
    org.springframework.data.domain.Page<UserTagAssignment> findByTagId(long tagId,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 一批用户各自当前**生效中**的分配数（V1.3.0 Story 8.2：选择器里就地预告「满 3 会顶掉最早的」）。
     *
     * <p>🔴 必须是批量：候选表一页 30 行，逐行问一次就是 30 条查询。
     * 口径与 {@link #findActiveByTag} 逐字一致（半开区间，{@code endsAt} 空 = 永久）。
     *
     * @return 每行 {@code [userId, count]}；**没有任何生效分配的用户不会出现在结果里**
     */
    @Query("""
            select a.userId, count(a) from UserTagAssignment a
             where a.userId in :userIds
               and a.startsAt <= :now
               and (a.endsAt is null or :now < a.endsAt)
             group by a.userId
            """)
    List<Object[]> countActiveByUsers(@Param("userIds") Collection<Long> userIds,
            @Param("now") Instant now);

}
