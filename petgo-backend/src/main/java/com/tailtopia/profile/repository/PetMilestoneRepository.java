package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.PetMilestone;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PetMilestoneRepository extends JpaRepository<PetMilestone, Long> {

    List<PetMilestone> findByPetProfileIdOrderBySortOrderAsc(long petProfileId);

    boolean existsByPetProfileId(long petProfileId);

    Optional<PetMilestone> findByPetProfileIdAndCode(long petProfileId, String code);

    void deleteByPetProfileId(long petProfileId);

    /**
     * 账号下所有宠物（不分 ACTIVE / READ_ONLY）在给定完整 code 上的 roster 行 id（V1.4.2 Story 2.1 · 账号级庆祝回报）。
     * 同模块联表（都在 profile 域）。
     */
    @Query("select m.id from PetMilestone m join PetProfile p on p.id = m.petProfileId "
            + "where p.ownerId = :ownerId and m.code in :codes")
    List<Long> findIdsByOwnerIdAndCodeIn(@Param("ownerId") long ownerId, @Param("codes") Collection<String> codes);
}
