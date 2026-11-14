package com.tailtopia.healthreminder.repository;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthReminderRepository extends JpaRepository<HealthReminder, Long> {

    /** 一只宠物的全部提醒行（重建读历史与对账用；量小）。 */
    List<HealthReminder> findByPetProfileId(long petProfileId);

    List<HealthReminder> findByPetProfileIdAndStatus(long petProfileId, ReminderStatus status);

    @Modifying
    @Query("delete from HealthReminder r where r.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
