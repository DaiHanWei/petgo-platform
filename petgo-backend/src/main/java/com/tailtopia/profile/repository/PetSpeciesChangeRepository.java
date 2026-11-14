package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.PetSpeciesChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 宠物类型修改记录（V1.5.0 pet-species · D-5）。 */
public interface PetSpeciesChangeRepository extends JpaRepository<PetSpeciesChange, Long> {

    /** 某只宠物的修改记录，新的在前（单宠查询 / 核对用；后台用户详情用下面的批量版，不逐宠调用）。 */
    List<PetSpeciesChange> findByPetProfileIdOrderByCreatedAtDesc(long petProfileId);

    /** 一个用户全部宠物的修改记录（后台用户详情一次批量取，不 N+1），新的在前。 */
    List<PetSpeciesChange> findByPetProfileIdInOrderByCreatedAtDescIdDesc(java.util.Collection<Long> petProfileIds);
}
