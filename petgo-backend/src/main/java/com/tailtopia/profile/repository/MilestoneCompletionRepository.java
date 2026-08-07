package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.service.MilestoneTimelineView;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MilestoneCompletionRepository extends JpaRepository<MilestoneCompletion, Long> {

    List<MilestoneCompletion> findByPetMilestoneIdIn(Collection<Long> petMilestoneIds);

    long countByPetMilestoneIdIn(Collection<Long> petMilestoneIds);

    Optional<MilestoneCompletion> findByPetMilestoneId(long petMilestoneId);

    boolean existsByPetMilestoneId(long petMilestoneId);

    boolean existsByLinkedContentId(long linkedContentId);

    void deleteByPetMilestoneIdIn(Collection<Long> petMilestoneIds);

    /**
     * 时间线只读视图：该宠物在锚点之前完成的里程碑（Story 3.2 · AC1）。
     *
     * <p>里程碑无独立 event_date —— 有效日期由 {@code completedAt} 的 UTC 日推导、与之单调同序，
     * 故复合锚点在本源上退化为单键上界（见 {@code TimelineAnchor#createdAtUpperBound()}）。
     *
     * <p>与 {@code PetMilestone} 的 join 属**同模块内**联表（都在 profile 域），不违反模块边界；
     * 跨模块（content / consult）一律经各自 service 接口取数。
     */
    @Query("select new com.tailtopia.profile.service.MilestoneTimelineView("
            + "m.code, m.level, c.completedAt, c.linkedContentId) "
            + "from MilestoneCompletion c join PetMilestone m on m.id = c.petMilestoneId "
            + "where m.petProfileId = :petProfileId and c.completedAt < :upperBound "
            + "order by c.completedAt desc")
    List<MilestoneTimelineView> findTimelineViewsBefore(@Param("petProfileId") long petProfileId,
            @Param("upperBound") Instant upperBound, Pageable page);
}
