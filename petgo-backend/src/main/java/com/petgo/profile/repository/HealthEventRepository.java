package com.petgo.profile.repository;

import com.petgo.profile.domain.ArchiveDecision;
import com.petgo.profile.domain.HealthEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthEventRepository extends JpaRepository<HealthEvent, Long> {

    boolean existsBySourceRef(String sourceRef);

    Optional<HealthEvent> findBySourceRef(String sourceRef);

    /** 时间线读：某宠物已存档的健康事件，createdAt 倒序游标分页（Story 2.5 → 2.4 聚合）。 */
    List<HealthEvent> findByPetIdAndArchiveDecisionAndCreatedAtLessThanOrderByCreatedAtDesc(
            long petId, ArchiveDecision decision, Instant before, Pageable pageable);

    /** 区间读（Story 2.4 R2 日历/当天）：某宠物已存档健康事件落 [from,to)，createdAt 升序。 */
    List<HealthEvent> findByPetIdAndArchiveDecisionAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            long petId, ArchiveDecision decision, Instant from, Instant to);

    /** 统计（Story 2.4 AC5「问诊 X 次」）：某宠物已存档健康事件数。 */
    long countByPetIdAndArchiveDecision(long petId, ArchiveDecision decision);

    /** Story 7.3：注销级联删除某宠物全部健康事件（先收集图片 key 再删表）。 */
    List<HealthEvent> findByPetId(long petId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByPetId(long petId);
}
