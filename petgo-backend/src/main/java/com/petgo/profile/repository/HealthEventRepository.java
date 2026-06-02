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
}
