package com.petgo.profile.repository;

import com.petgo.profile.domain.MilestoneCompletion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilestoneCompletionRepository extends JpaRepository<MilestoneCompletion, Long> {

    List<MilestoneCompletion> findByPetMilestoneIdIn(Collection<Long> petMilestoneIds);

    Optional<MilestoneCompletion> findByPetMilestoneId(long petMilestoneId);

    boolean existsByPetMilestoneId(long petMilestoneId);

    boolean existsByLinkedContentId(long linkedContentId);

    void deleteByPetMilestoneIdIn(Collection<Long> petMilestoneIds);
}
