package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.MilestoneShare;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilestoneShareRepository extends JpaRepository<MilestoneShare, Long> {

    Optional<MilestoneShare> findByShareToken(String shareToken);

    Optional<MilestoneShare> findByPetProfileIdAndCode(long petProfileId, String code);

    /** 账号注销 / 档案删除级联清理（护栏 D1）。 */
    void deleteByPetProfileId(long petProfileId);
}
