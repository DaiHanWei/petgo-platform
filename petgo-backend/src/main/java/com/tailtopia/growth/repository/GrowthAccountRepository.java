package com.tailtopia.growth.repository;

import com.tailtopia.growth.domain.GrowthAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 成长值账户仓储（V1.4.0 ① Story 1.1）。
 *
 * <p>🛡 并发：同一用户的入账 / 扣回一律先 {@link #insertIfAbsent} 再 {@link #lockById}（行锁），从此串行——
 * 每日额度、里程碑 5 次判定、累计不为负都依赖这把锁。
 */
public interface GrowthAccountRepository extends JpaRepository<GrowthAccount, Long> {

    /** 懒建账户行（并发安全）。1 = 新建 / 0 = 已存在。 */
    @Modifying
    @Query(value = "INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, "
            + "backfill_popup_pending, version, created_at, updated_at) "
            + "VALUES (:userId, 0, 0, 1, false, 0, now(), now()) "
            + "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId);

    /**
     * 降级后读时校正（Story 2.1 · AC3.3）：待弹的 {@code min(to, level) <= from} 时原子清掉两列。条件写在 SQL 里，
     * 不依赖内存实体（同事务里已读出的实体可能已过时）；并发入账若刚推进了待弹，条件不再成立、不会被误清。
     */
    @Modifying
    @Query(value = "UPDATE growth_accounts SET celebrate_from_level = NULL, celebrate_to_level = NULL, "
            + "updated_at = now() WHERE user_id = :userId AND celebrate_from_level IS NOT NULL "
            + "AND LEAST(celebrate_to_level, level) <= celebrate_from_level", nativeQuery = true)
    int clearStaleCelebration(@Param("userId") long userId);

    /** {@code SELECT … FOR UPDATE}：锁住账户行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from GrowthAccount a where a.userId = :userId")
    Optional<GrowthAccount> lockById(@Param("userId") long userId);
}
