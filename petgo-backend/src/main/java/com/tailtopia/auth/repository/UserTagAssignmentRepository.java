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
}
