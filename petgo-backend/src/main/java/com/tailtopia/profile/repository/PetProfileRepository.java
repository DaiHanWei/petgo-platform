package com.petgo.profile.repository;

import com.petgo.profile.domain.PetProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {

    Optional<PetProfile> findByOwnerId(long ownerId);

    boolean existsByOwnerId(long ownerId);

    Optional<PetProfile> findByCardToken(String cardToken);

    /** 建档满阈值天数的档案（陪伴里程碑定时扫描，Story 8.3 C-M8 陪伴满 30 天）。 */
    List<PetProfile> findByCreatedAtLessThanEqual(Instant threshold);
}
