package com.tailtopia.tailsonality.repository;

import com.tailtopia.tailsonality.domain.TailsonalityResult;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TailsonalityResultRepository extends JpaRepository<TailsonalityResult, Long> {

    List<TailsonalityResult> findByPetProfileIdOrderByCreatedAtDescIdDesc(long petProfileId);

    /** 该宠物有没有任何一条结果（V1.3.2 Story 4.5 分享奖励资格）。 */
    boolean existsByPetProfileId(long petProfileId);

    Optional<TailsonalityResult> findByPublicTokenAndPetProfileId(String publicToken, long petProfileId);

    /** 解锁发起（Story 3.2 · AC1.2）：{@code SELECT … FOR UPDATE}，同一结果的并发发起串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TailsonalityResult> findForUpdateByPublicTokenAndPetProfileId(String publicToken, long petProfileId);

    /** 删档 / 注销（AC7.2）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TailsonalityResult r where r.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
