package com.petgo.profile.repository;

import com.petgo.profile.domain.PetProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {

    Optional<PetProfile> findByOwnerId(long ownerId);

    boolean existsByOwnerId(long ownerId);

    Optional<PetProfile> findByCardToken(String cardToken);
}
