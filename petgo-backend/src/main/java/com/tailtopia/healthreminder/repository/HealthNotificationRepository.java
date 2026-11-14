package com.tailtopia.healthreminder.repository;

import com.tailtopia.healthreminder.domain.HealthNotification;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthNotificationRepository extends JpaRepository<HealthNotification, Long> {

    Optional<HealthNotification> findByPublicToken(String publicToken);

    @Modifying
    @Query("delete from HealthNotification n where n.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
