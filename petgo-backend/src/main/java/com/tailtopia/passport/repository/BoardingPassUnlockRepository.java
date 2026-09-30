package com.tailtopia.passport.repository;

import com.tailtopia.passport.domain.BoardingPassUnlock;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code boarding_pass_unlocks} 仓储（V1.3.2 Story 3.5）。 */
public interface BoardingPassUnlockRepository extends JpaRepository<BoardingPassUnlock, Long> {

    /** 该宠物的全部行（含 superseded；列表按 placeId 映射时自行过滤）。 */
    List<BoardingPassUnlock> findByPetProfileId(long petProfileId);

    Optional<BoardingPassUnlock> findByPetProfileIdAndPlaceId(long petProfileId, long placeId);

    /** 发起（AC5.2）：先 upsert 再加锁读。 */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO boarding_pass_unlocks (pet_profile_id, place_id, public_token) "
            + "VALUES (:petId, :placeId, :token) ON CONFLICT (pet_profile_id, place_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("petId") long petId, @Param("placeId") long placeId, @Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BoardingPassUnlock b where b.petProfileId = :petId and b.placeId = :placeId")
    Optional<BoardingPassUnlock> findForUpdate(@Param("petId") long petId, @Param("placeId") long placeId);

    /** 删档（AC11）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from BoardingPassUnlock b where b.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
