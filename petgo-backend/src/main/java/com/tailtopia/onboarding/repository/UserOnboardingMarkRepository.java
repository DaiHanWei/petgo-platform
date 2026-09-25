package com.tailtopia.onboarding.repository;

import com.tailtopia.onboarding.domain.UserOnboardingMark;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserOnboardingMarkRepository extends JpaRepository<UserOnboardingMark, Long> {

    /** 这个用户已置位的全部标记。一次取完 —— 引导键总共就几个，不值得逐个查。 */
    List<UserOnboardingMark> findByUserId(long userId);

    boolean existsByUserIdAndMarkKey(long userId, String markKey);

    /**
     * 幂等置位：已置位则什么都不做，返回 0。
     *
     * <p>🔴 不用「saveAndFlush 撞键再 catch」：约束异常已让事务 rollback-only，提交时仍会 500。
     */
    @Modifying
    @Query(value = "INSERT INTO user_onboarding_marks (user_id, mark_key, first_seen_at) "
            + "VALUES (:userId, :markKey, now()) "
            + "ON CONFLICT ON CONSTRAINT uq_user_onboarding_marks DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("markKey") String markKey);

    /** 账号注销级联（D1）：纯个人数据，物理删除。幂等。 */
    void deleteByUserId(long userId);
}
