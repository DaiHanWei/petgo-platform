package com.tailtopia.passport.repository;

import com.tailtopia.passport.domain.PassportSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code passport_snapshots} 仓储（V1.3.2 Story 3.4）。 */
public interface PassportSnapshotRepository extends JpaRepository<PassportSnapshot, Long> {

    /** 已买版本（新 → 旧）。 */
    List<PassportSnapshot> findByPetProfileIdAndPaidAtIsNotNullOrderByPaidAtDescIdDesc(long petProfileId);

    /** 未付快照（新 → 旧）：同 hash 复用最新一行。 */
    List<PassportSnapshot> findByPetProfileIdAndPaidAtIsNullOrderByCreatedAtDescIdDesc(long petProfileId);

    long countByPetProfileIdAndPaidAtIsNotNull(long petProfileId);

    Optional<PassportSnapshot> findByPublicTokenAndPetProfileId(String publicToken, long petProfileId);

    /** 删档（AC9）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PassportSnapshot s where s.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
