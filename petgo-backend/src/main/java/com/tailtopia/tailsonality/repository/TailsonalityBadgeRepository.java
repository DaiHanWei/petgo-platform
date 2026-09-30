package com.tailtopia.tailsonality.repository;

import com.tailtopia.tailsonality.domain.TailsonalityBadge;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TailsonalityBadgeRepository extends JpaRepository<TailsonalityBadge, Long> {

    /** 佩戴 / 切换（AC2.2）：upsert，同宠物并发切换不撞主键。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO tailsonality_badges (pet_profile_id, result_id) VALUES (:petId, :resultId)
            ON CONFLICT (pet_profile_id) DO UPDATE SET result_id = EXCLUDED.result_id, updated_at = now()
            """, nativeQuery = true)
    int upsert(@Param("petId") long petId, @Param("resultId") long resultId);

    /** 卸下（D-16）/ 删档（AC8）。幂等。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TailsonalityBadge b where b.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);

    /** 当前佩戴的结果 id（不校验解锁态；小标下发走 {@code TailsonalityBadgeQuery}）。 */
    @Query("select b.resultId from TailsonalityBadge b where b.petProfileId = :petId")
    Optional<Long> findResultIdByPetProfileId(@Param("petId") long petId);

    /** 佩戴 + 已解锁 → 4 字母（AC4 统一规则）。 */
    @Query("""
            select r.typeCode from TailsonalityBadge b, TailsonalityResult r
             where b.petProfileId = :petId and r.id = b.resultId and r.unlockedAt is not null
            """)
    Optional<String> findEquippedUnlockedLetters(@Param("petId") long petId);
}
