package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthEventRepository extends JpaRepository<HealthEvent, Long> {

    boolean existsBySourceRef(String sourceRef);

    /** 是否【已存档】(ARCHIVED，非 SKIPPED)：结果页据此隐藏保存按钮（bug 20260721-333）。 */
    boolean existsBySourceRefAndArchiveDecision(String sourceRef, ArchiveDecision decision);

    Optional<HealthEvent> findBySourceRef(String sourceRef);

    /** 时间线读：某宠物已存档的健康事件，createdAt 倒序游标分页（Story 2.5 → 2.4 聚合）。 */
    List<HealthEvent> findByPetIdAndArchiveDecisionAndCreatedAtLessThanOrderByCreatedAtDesc(
            long petId, ArchiveDecision decision, Instant before, Pageable pageable);

    /** 区间读（Story 2.4 R2 日历/当天）：某宠物已存档健康事件落 [from,to)，createdAt 升序。 */
    List<HealthEvent> findByPetIdAndArchiveDecisionAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            long petId, ArchiveDecision decision, Instant from, Instant to);

    /** 统计（Story 2.4 AC5「问诊 X 次」）：某宠物已存档健康事件数。 */
    /**
     * 时间线取数（V1.1.6 修正）：按<b>就诊日期</b>的复合锚点取「锚点之前」的一批。
     *
     * <p>🔴 <b>为什么不能按 created_at 取。</b>问诊存档有独立的 {@code event_date}（就诊那天），
     * 而 {@code created_at} 是<b>归档进档案的时刻</b> —— 一次三个月前的问诊今天才归档，
     * 两者相差三个月。排序按就诊日期、取数按归档时刻，就是<b>两把尺子</b>：
     * 该条在排序上落到三个月前的位置、在翻页上却被当成最新记录，跨页时丢失或重复。
     * （成长内容早先正是踩了这个坑才做的锚点重构，见 {@code TimelineAnchor} 的说明。）
     *
     * <p>序：就诊日期倒序 → 同日归档时刻倒序，与 {@code TimelineAnchor} 的全局序一致。
     */
    @Query("""
            SELECT e FROM HealthEvent e
            WHERE e.petId = :petId
              AND e.archiveDecision = :decision
              AND (e.eventDate < :anchorDate
                   OR (e.eventDate = :anchorDate AND e.createdAt < :anchorKey))
            ORDER BY e.eventDate DESC, e.createdAt DESC
            """)
    List<HealthEvent> findBeforeAnchor(
            @Param("petId") long petId,
            @Param("decision") ArchiveDecision decision,
            @Param("anchorDate") LocalDate anchorDate,
            @Param("anchorKey") Instant anchorKey,
            Pageable pageable);

    /**
     * 日历 / 某天详情取数（V1.1.6 修正）：按<b>就诊日期</b>落在 [from, to] 区间。
     *
     * <p>🔴 改之前这里查的是 {@code created_at} 区间，于是「一次三个月前的问诊今天才归档」
     * 会显示在<b>今天</b>那一格 —— 数据本身没错（就诊日期一直存着），是取数读错了字段。
     */
    @Query("""
            SELECT e FROM HealthEvent e
            WHERE e.petId = :petId
              AND e.archiveDecision = :decision
              AND e.eventDate BETWEEN :from AND :to
            ORDER BY e.eventDate ASC, e.createdAt ASC
            """)
    List<HealthEvent> findByEventDateBetween(
            @Param("petId") long petId,
            @Param("decision") ArchiveDecision decision,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    long countByPetIdAndArchiveDecision(long petId, ArchiveDecision decision);

    /** Story 7.3：注销级联删除某宠物全部健康事件（先收集图片 key 再删表）。 */
    List<HealthEvent> findByPetId(long petId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByPetId(long petId);
}
