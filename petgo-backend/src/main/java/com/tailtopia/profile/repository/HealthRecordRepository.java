package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.HealthRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface HealthRecordRepository extends JpaRepository<HealthRecord, Long> {

    /** 某宠物的健康记录，按发生日期倒序（同日按 id 倒序稳定）。 */
    List<HealthRecord> findByPetProfileIdOrderByEventDateDescIdDesc(long petProfileId);

    /**
     * 某宠物的结构化健康记录条数（V1.1.2 · UI 稿 A4 近空态）。
     *
     * <p>Diary 页头「健康记录」入口的副文案按 0 / 非 0 切换（0 → 「还没有记录」），
     * 避免刚建档的用户看到「疫苗 · 驱虫 · 病历」这种**像是已有内容**的固定描述。
     */
    long countByPetProfileId(long petProfileId);

    /** 某宠物在 [from, to] 内（按 event_date）的健康记录，日期升、同日 id 升——日历角标分类图标（bug 20260722-352）。 */
    List<HealthRecord> findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
            long petProfileId, LocalDate from, LocalDate to);

    /** 归属校验取单条（记录须归属该宠物，否则空 → 调用方 404 防枚举）。 */
    Optional<HealthRecord> findByIdAndPetProfileId(long id, long petProfileId);

    /**
     * 时间线只读视图：该宠物在**统一锚点**之前的结构化健康记录（Story 3.2 · AC1）。
     *
     * <p>本表有独立 {@code event_date}（用户填的事件日，可与录入时刻不同日），故必须用
     * 复合锚点「事件日期 + 同日排序键」严格小于比较 —— 只用 createdAt 会与全局序脱节，
     * 正是 Story 3.1 修掉的那类缺陷。
     */
    @Query("select r from HealthRecord r where r.petProfileId = :petProfileId "
            + "and (r.eventDate < :eventDate "
            + "  or (r.eventDate = :eventDate and r.createdAt < :sameDayKey)) "
            + "order by r.eventDate desc, r.createdAt desc")
    List<HealthRecord> findBeforeAnchor(@Param("petProfileId") long petProfileId,
            @Param("eventDate") LocalDate eventDate, @Param("sameDayKey") Instant sameDayKey,
            Pageable page);

    /** 该宠物是否有 ≥1 条健康记录（Story 7.3 第 6 新手任务判定）。 */
    boolean existsByPetProfileId(long petProfileId);

    /** 档案删除级联硬删（Story 7.1 · PDP）。 */
    @Transactional
    void deleteByPetProfileId(long petProfileId);
}
