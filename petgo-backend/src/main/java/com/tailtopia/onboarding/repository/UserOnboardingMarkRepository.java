package com.tailtopia.onboarding.repository;

import com.tailtopia.onboarding.domain.UserOnboardingMark;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserOnboardingMarkRepository extends JpaRepository<UserOnboardingMark, Long> {

    /** 这个用户已置位的全部标记。一次取完 —— 引导键总共就几个，不值得逐个查。 */
    List<UserOnboardingMark> findByUserId(long userId);

    boolean existsByUserIdAndMarkKey(long userId, String markKey);

    /** 账号注销级联（D1）：纯个人数据，物理删除。幂等。 */
    void deleteByUserId(long userId);
}
