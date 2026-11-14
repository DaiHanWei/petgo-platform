package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.PetSpeciesChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 宠物类型修改记录（V1.5.0 pet-species · D-5）。 */
public interface PetSpeciesChangeRepository extends JpaRepository<PetSpeciesChange, Long> {

    /** 某只宠物的修改记录，新的在前（后台用户详情 G1 用）。 */
    List<PetSpeciesChange> findByPetProfileIdOrderByCreatedAtDesc(long petProfileId);
}
