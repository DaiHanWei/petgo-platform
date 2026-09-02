package com.tailtopia.share.repository;

import com.tailtopia.share.domain.IdCardShareReward;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdCardShareRewardRepository extends JpaRepository<IdCardShareReward, Long> {

    /** 这个档案拿过没有（🔴 去重按档案，不按卡）。 */
    Optional<IdCardShareReward> findByPetProfileId(long petProfileId);

    /** 某账号在某个 WIB 当地日期已拿过几次（日上限判定）。 */
    long countByUserIdAndShareDate(long userId, LocalDate shareDate);

    /** 账号注销级联（Story 7.3）：发放留痕随 PawCoin 钱包/流水同口径物理删除。幂等。 */
    void deleteByUserId(long userId);
}
