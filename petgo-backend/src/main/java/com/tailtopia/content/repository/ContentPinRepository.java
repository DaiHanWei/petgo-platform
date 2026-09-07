package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentPin;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 顶置排期仓储（V1.1.6 Story 4.1）。
 *
 * <h2>🔴 这里的时间窗判定与 {@code ScheduleWindow} 必须给出同样答案</h2>
 * 过滤只能写在 SQL 的 WHERE 里 —— 捞出来再在内存里筛会破坏分页与游标契约，
 * 所以物理上没法与 Java 侧共用同一行代码。两侧一致性由
 * {@code ContentPinWindowParityIntegrationTest} 用一组边界时刻逐一对照来堵。
 *
 * <p>口径：生效中 = {@code startsAt <= now < COALESCE(terminatedAt, endsAt)}。
 * <b>左闭右开</b> —— 结束那一刻即算已结束。写入时保证 {@code terminatedAt <= endsAt}，
 * 故这里可以直接 COALESCE 而不必取两者较小值。
 */
public interface ContentPinRepository extends JpaRepository<ContentPin, Long> {

    /** 某坑位当前生效中的排期（重叠校验保证至多一条；仍取最新一条兜底）。 */
    @Query("""
            select p from ContentPin p
             where p.slot = :slot
               and p.startsAt <= :now
               and :now < coalesce(p.terminatedAt, p.endsAt)
             order by p.startsAt desc
            """)
    List<ContentPin> findActive(@Param("slot") String slot, @Param("now") Instant now);

    default Optional<ContentPin> findActiveOne(String slot, Instant now) {
        return findActive(slot, now).stream().findFirst();
    }

    /** 后台列表（Story 11.1）：某坑位全部排期，含已结束的历史，开始时间倒序。 */
    List<ContentPin> findBySlotOrderByStartsAtDesc(String slot);

    /**
     * 与给定窗口重叠的排期（重叠校验用）。
     *
     * <p>🛡 **首尾相接不算重叠**（左闭右开）：10:00–12:00 与 12:00–14:00 是合法排期。
     * 比较用的是**生效意义上的结束时刻**，因此一条被提前结束的配置腾出的时段可以再排。
     */
    @Query("""
            select p from ContentPin p
             where p.slot = :slot
               and (:excludeId is null or p.id <> :excludeId)
               and p.startsAt < :endsAt
               and :startsAt < coalesce(p.terminatedAt, p.endsAt)
            """)
    List<ContentPin> findOverlapping(@Param("slot") String slot,
            @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt,
            @Param("excludeId") Long excludeId);

    /**
     * 下架联动：把引用该内容、且尚未结束的排期提前结束（幂等 + 单条 SQL）。
     *
     * <p>{@code terminatedAt is null} 保证重复触发不改时刻；{@code endsAt > :at} 保证
     * 不会把已经自然结束的排期"回填"一个更晚的结束时刻，也满足 DB 的 {@code terminated_at <= ends_at} 约束。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ContentPin p set p.terminatedAt = :at, p.updatedAt = :at
             where p.contentId = :contentId
               and p.terminatedAt is null
               and p.endsAt > :at
            """)
    int terminateByContent(@Param("contentId") long contentId, @Param("at") Instant at);

    /**
     * 注销联动：某作者名下内容全部不再可展示时，一并结束引用它们的排期。
     *
     * <p>⚠️ 注销走的是**批量隐藏**（内容不软删、无逐条 id），拿不到可发事件的粒度，
     * 故用子查询直接收口。与 {@link #terminateByContent} 同一套幂等守卫。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ContentPin p set p.terminatedAt = :at, p.updatedAt = :at
             where p.contentId in (select c.id from ContentPost c where c.authorId = :authorId)
               and p.terminatedAt is null
               and p.endsAt > :at
            """)
    int terminateByAuthor(@Param("authorId") long authorId, @Param("at") Instant at);
}
