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
    @Modifying
    @Query("update IdCard c set c.profileDeletedAt = :at where c.userId = :userId and c.profileDeletedAt is null")
    void markProfileDeleted(@Param("userId") long userId, @Param("at") Instant at);
}
