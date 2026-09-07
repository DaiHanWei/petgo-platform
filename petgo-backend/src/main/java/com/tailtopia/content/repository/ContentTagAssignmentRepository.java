package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentTagAssignment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 内容装饰标签分配仓储（V1.1.6 Story 5.2）。
 *
 * <h2>🛡 只有批量方法</h2>
 * 三处展示位（首页卡 / 详情页 / 宠物日记页）一律批量（AD-11）。
 * 这里不给"按单条内容取"的方法，是为了让逐条查连写都写不出来 ——
 * 单条就传一个只有一个元素的集合。
 *
 * <h2>时间窗口径与 {@code ScheduleWindow} 一致</h2>
 * 生效中 = {@code startsAt <= now AND (endsAt IS NULL OR now < endsAt)}，
 * 左闭右开，{@code endsAt} 为空 = 永久。
 */
public interface ContentTagAssignmentRepository extends JpaRepository<ContentTagAssignment, Long> {

    /**
     * 与给定时间窗**重叠**的分配记录（V1.1.6 Story 11.6：一条内容最多一个装饰标签）。
     *
     * <p>🔴 判据是<b>窗口是否重叠</b>，不是"这条内容有没有别的标签记录" ——
     * 「本周最佳」下周一到期、「本月最佳」下周二开始是<b>正常排期</b>，不该被拒。
     *
     * <p>重叠的定义（半开区间，{@code endsAt} 为 null = 永久）：
     * <pre>
     *   新窗 [s, e) 与旧窗 [S, E) 重叠  ⟺  (E is null 或 s &lt; E) 且 (e is null 或 S &lt; e)
     * </pre>
     * ⚠️ 用 {@code <} 而不是 {@code <=}：旧窗 22:00 结束、新窗 22:00 开始是<b>接续</b>不是重叠。
     * 写成 {@code <=} 会把最常见的排期方式（前一个到点、后一个接上）判成冲突。
     *
     * <p>🛡 {@code excludeId} 用于「改这条分配本身」时排除自己（当前无此入口，留参数免得日后
     * 加编辑功能时把自己判成冲突）。
     *
     * <p>🔴 <b>为什么可空参数都配了一个布尔标志</b>：直接写 {@code :endsAt is null} 会让
     * PostgreSQL 推不出 NULL 参数的类型，报 <b>42P18 could not determine data type</b> ——
     * 本仓 {@code findFeed} 的注释早就记过这一条，本 story 又原地踩了一次。
     * 用布尔标志门控之后，{@code endsAt} 只与同类型的列比较，类型即可定。
     */
    @Query("""
            select a from ContentTagAssignment a
             where a.postId = :postId
               and (:hasExclude = false or a.id <> :excludeId)
               and (a.endsAt is null or :startsAt < a.endsAt)
               and (:hasEnd = false or a.startsAt < :endsAt)
             order by a.startsAt asc
            """)
    List<ContentTagAssignment> findOverlapping(@Param("postId") long postId,
            @Param("startsAt") Instant startsAt,
            @Param("hasEnd") boolean hasEnd,
            @Param("endsAt") Instant endsAt,
            @Param("hasExclude") boolean hasExclude,
            @Param("excludeId") Long excludeId);

    @Query("""
            select a, t from ContentTagAssignment a, ContentTag t
             where a.tagId = t.id
               and a.postId in :postIds
               and a.startsAt <= :now
               and (a.endsAt is null or :now < a.endsAt)
             order by a.startsAt desc, a.id desc
            """)
    List<Object[]> findActiveWithTag(@Param("postIds") Collection<Long> postIds,
            @Param("now") Instant now);

    /**
     * 后台「按标签」维度（Story 11.2）：某标签当前**生效中**的分配记录。
     *
     * <p>口径与 {@link #findActiveWithTag} 一致：{@code startsAt <= now} 且
     * （{@code endsAt} 为空 = 永久 或 {@code now < endsAt}）。🛡 无状态列、无扫描器。
     */
    @Query("""
            select a from ContentTagAssignment a
             where a.tagId = :tagId
               and a.startsAt <= :now
               and (a.endsAt is null or :now < a.endsAt)
             order by a.startsAt desc
            """)
    List<ContentTagAssignment> findActiveByTag(@Param("tagId") long tagId, @Param("now") Instant now);

    /** 后台「按内容」维度（Story 11.2）：某条内容的全部分配记录（含已失效的历史）。 */
    List<ContentTagAssignment> findByPostIdOrderByStartsAtDesc(long postId);
}
