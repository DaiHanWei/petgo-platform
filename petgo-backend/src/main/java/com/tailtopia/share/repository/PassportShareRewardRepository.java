package com.tailtopia.share.repository;

import com.tailtopia.share.domain.PassportShareReward;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** passport_share_rewards 账本（V1.3.2 Story 4.5）。 */
public interface PassportShareRewardRepository extends JpaRepository<PassportShareReward, Long> {

    /** 这只宠物这种卡拿过没有（🔴 去重 = 宠物 × 卡类型）。 */
    Optional<PassportShareReward> findByPetProfileIdAndCardType(long petProfileId, String cardType);

    /** 某账号在某个 WIB 当地日期已拿过几次（日上限判定）。 */
    long countByUserIdAndShareDate(long userId, LocalDate shareDate);

    /** 账号注销级联（D1）：与同胞表 id_card_share_rewards / age_card_share_rewards 同口径物理删除。幂等。 */
    void deleteByUserId(long userId);
}
