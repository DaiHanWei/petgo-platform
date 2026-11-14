package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.UserMilestoneFlag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 账号级里程碑 flag 读写（V1.4.2 social-topic Story 2.1 · D-4）。 */
public interface UserMilestoneFlagRepository extends JpaRepository<UserMilestoneFlag, UserMilestoneFlag.Key> {

    /**
     * 幂等登记 flag：已存在则什么都不做，返回实际插入行数（0 / 1）。调用方<b>只在返回 1 时</b>继续
     * （并发两次触发只有一条走下去，AC8）。
     *
     * <p>⚠️ 必须用 {@code ON CONFLICT DO NOTHING}，不要改成「save + catch 唯一约束异常」——
     * 约束异常穿出 repo 代理时事务已被标记 rollback-only（同 {@code UserFollowRepository#insertIfAbsent}）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_milestone_flags (user_id, flag_key, occurred_at)
            VALUES (:userId, :flagKey, now())
            ON CONFLICT (user_id, flag_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("flagKey") String flagKey);

    /** 该账号已触发的 flag_key 列表。 */
    @Query("select f.flagKey from UserMilestoneFlag f where f.userId = :userId")
    List<String> findFlagKeysByUserId(@Param("userId") long userId);

    /** 注销清理（D1/D2）：users 行只匿名化不删，FK CASCADE 在注销路径不触发，必须显式删。 */
    /**
     * 账号级「已庆祝」置位（V1.5.0 onboarding-recall Story 2.2 · AC9）：只置 NULL 的（重复回报幂等）。
     *
     * @return 本次置位行数（0 = 没有该 flag 或已庆祝过）
     */
    @Modifying
    @Query(value = """
            UPDATE user_milestone_flags SET celebrated_at = :at
             WHERE user_id = :userId AND flag_key = :flagKey AND celebrated_at IS NULL
            """, nativeQuery = true)
    int markCelebrated(@Param("userId") long userId, @Param("flagKey") String flagKey,
            @Param("at") java.time.Instant at);

    /** 该账号全部 flag 行（V1.5.0 Story 2.3 账号成就接口 / 列表账号组）。 */
    List<UserMilestoneFlag> findAllByUserId(long userId);

    /** 该账号是否已有某个 flag（V1.5.0：Lulus Pemula 已解锁判定）。 */
    boolean existsByUserIdAndFlagKey(long userId, String flagKey);

    /** 该账号某个 flag 行（V1.5.0 Story 2.3 账号成就接口读 {@code celebrated_at}）。 */
    java.util.Optional<UserMilestoneFlag> findByUserIdAndFlagKey(long userId, String flagKey);

    @Modifying
    @Query("delete from UserMilestoneFlag f where f.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
