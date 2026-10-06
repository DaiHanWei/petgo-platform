package com.tailtopia.place.repository;

import com.tailtopia.place.domain.PlaceCheckinPet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code place_checkin_pets} 仓储（V1.3.2 batch-a Story 1.1）。 */
public interface PlaceCheckinPetRepository extends JpaRepository<PlaceCheckinPet, PlaceCheckinPet.Key> {

    /** 删档：删该宠物的全部打卡关联（AC6.1，先于孤儿打卡行）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PlaceCheckinPet cp where cp.id.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
