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
