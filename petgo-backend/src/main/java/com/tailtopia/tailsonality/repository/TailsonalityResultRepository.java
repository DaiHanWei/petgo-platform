package com.tailtopia.tailsonality.repository;

import com.tailtopia.tailsonality.domain.TailsonalityResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TailsonalityResultRepository extends JpaRepository<TailsonalityResult, Long> {

    List<TailsonalityResult> findByPetProfileIdOrderByCreatedAtDescIdDesc(long petProfileId);

    Optional<TailsonalityResult> findByPublicTokenAndPetProfileId(String publicToken, long petProfileId);

    /** 删档 / 注销（AC7.2）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TailsonalityResult r where r.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
