package com.tailtopia.share.repository;

import com.tailtopia.share.domain.AgeCardShareReward;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgeCardShareRewardRepository extends JpaRepository<AgeCardShareReward, Long> {

    /**
     * 这次分享上报过没有（🔴 去重按**幂等键**，不按宠物档案 —— 决策 A-8）。
     *
     * <p>⚠️ 本接口刻意**没有** {@code findByPetProfileId} 之类的方法：
     * 年龄卡不做档案级去重，提供那样一个方法就是在邀请别人把它重新做出来。
     */
    Optional<AgeCardShareReward> findByIdempotencyKey(String idempotencyKey);

    /** 某账号在某个 WIB 当地日期已拿过几次（日上限判定）。 */
    long countByUserIdAndShareDate(long userId, LocalDate shareDate);

    /** 账号注销级联（D1）：与同胞表 id_card_share_rewards 同口径物理删除。幂等。 */
    void deleteByUserId(long userId);
}
