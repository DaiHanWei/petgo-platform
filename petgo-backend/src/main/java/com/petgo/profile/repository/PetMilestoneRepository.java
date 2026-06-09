package com.petgo.profile.repository;

import com.petgo.profile.domain.PetMilestone;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PetMilestoneRepository extends JpaRepository<PetMilestone, Long> {

    List<PetMilestone> findByPetProfileIdOrderBySortOrderAsc(long petProfileId);

    boolean existsByPetProfileId(long petProfileId);

    Optional<PetMilestone> findByPetProfileIdAndCode(long petProfileId, String code);

    void deleteByPetProfileId(long petProfileId);
}
