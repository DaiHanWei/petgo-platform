package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.PetProfile;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {

    Optional<PetProfile> findByOwnerId(long ownerId);

    /** 批量取多账号的档案（兽医工作台列表富化，避免逐条 N+1）。 */
    List<PetProfile> findByOwnerIdIn(Collection<Long> ownerIds);

    boolean existsByOwnerId(long ownerId);

    Optional<PetProfile> findByCardToken(String cardToken);

    /** 建档满阈值天数的档案（陪伴里程碑定时扫描，Story 8.3 C-M8 陪伴满 30 天）。 */
    List<PetProfile> findByCreatedAtLessThanEqual(Instant threshold);
}
