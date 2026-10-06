package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.IdCard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdCardRepository extends JpaRepository<IdCard, Long> {

    /** 历史列表：某用户全部卡，建卡时刻倒序（Story 6-7）。可见性过滤在 service 层（付费卡恒可见）。 */
    List<IdCard> findByUserIdOrderByCreatedAtDesc(long userId);

    /** 单卡详情（归属校验）：非本人返回空 → 上层 404 防枚举。 */
    Optional<IdCard> findByIdAndUserId(long id, long userId);

    /**
     * 档案删除打标（V108，2026-08-19 决策）：给该用户全部未打标卡记录档案删除时刻。
     * 付费卡照打标——可见性规则（hdUnlocked 恒可见）保证其展示不受影响；只打未打标行保证幂等
     * （删档→重建→再删档时，老卡保留首次删除时刻）。
     */
    /**
     * 宠物护照号源（V1.3.2 Story 1.2 · AD-6 / D-6）：本人、未打删档标、建于当前宠物建档之后、
     * 有护照号、物种段一致、且该号未被任何 {@code pet_passports} 占用的<b>最早一张</b>卡的护照号。
     *
     * <p>🔴 每个条件都在防一个真实事故（story 1.2 关键设计点）；「未被占用」必须在 SQL 里判 ——
     * 撞了 {@code uq_pet_passports_passport_no} 在 PG 里会中止整个打卡事务。只读，绝不写 {@code id_cards}。
     */
    @Query(value = "SELECT c.passport_no FROM id_cards c "
            + "WHERE c.user_id = :userId AND c.profile_deleted_at IS NULL "
            + "AND c.created_at >= :petCreatedAt AND c.passport_no IS NOT NULL "
            + "AND substring(c.passport_no, 3, 2) = :speciesCode "
            + "AND NOT EXISTS (SELECT 1 FROM pet_passports pp WHERE pp.passport_no = c.passport_no) "
            + "ORDER BY c.created_at ASC, c.id ASC LIMIT 1", nativeQuery = true)
    Optional<String> findReusableKtpPassportNo(@Param("userId") long userId,
            @Param("petCreatedAt") Instant petCreatedAt, @Param("speciesCode") String speciesCode);

    @Modifying
    @Query("update IdCard c set c.profileDeletedAt = :at where c.userId = :userId and c.profileDeletedAt is null")
    void markProfileDeleted(@Param("userId") long userId, @Param("at") Instant at);
}
