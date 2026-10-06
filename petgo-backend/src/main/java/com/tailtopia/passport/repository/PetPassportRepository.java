package com.tailtopia.passport.repository;

import com.tailtopia.passport.domain.PetPassport;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code pet_passports} 仓储（V1.3.2 Story 1.2）。 */
public interface PetPassportRepository extends JpaRepository<PetPassport, Long> {

    Optional<PetPassport> findByPetProfileId(Long petProfileId);

    /** 快照发起串行化（V1.3.2 Story 3.4 · AC3.5）：锁该宠物的护照行，同宠并发发起只产生一个未付快照。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PetPassport p where p.petProfileId = :petId")
    Optional<PetPassport> findForUpdateByPetProfileId(@Param("petId") long petId);

    /**
     * 幂等签发：并发两次只产生一行（AC1.4）。
     *
     * <p>🔴 用<b>不带冲突目标</b>的 {@code ON CONFLICT DO NOTHING}：既吸收 {@code uq_pet_passports_pet}
     * 的并发，也吸收 {@code uq_pet_passports_passport_no} 的极端撞号 —— 后者若抛错会中止整个打卡事务
     * （PG 事务内任一语句失败即整笔作废）。返回 0 时调用方回读 / 换号重试。
     *
     * @return 实际插入行数（0 = 已存在或撞号）
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO pet_passports (pet_profile_id, passport_no, source, issued_at) "
            + "VALUES (:petId, :passportNo, :source, :issuedAt) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("petId") long petId, @Param("passportNo") String passportNo,
            @Param("source") String source, @Param("issuedAt") Instant issuedAt);

    /** 删档 / 注销（AC7.1）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PetPassport p where p.petProfileId = :petId")
    int deleteByPetProfileId(@Param("petId") long petId);
}
