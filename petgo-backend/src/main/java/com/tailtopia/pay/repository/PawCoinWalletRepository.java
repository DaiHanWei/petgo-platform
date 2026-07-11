package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.PawCoinWallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PawCoin 钱包仓储（Story 1.2）。<b>并发非负核心</b>：{@link #applyDelta} 单行原子条件 UPDATE
 * 自带行锁、天然串行化同一钱包的并发扣减，{@code WHERE balance + :delta >= 0} 保证不越负
 * （返回 0 行 = 余额不足/无钱包 → 拒绝）。<b>禁应用层读改写</b>（并发丢更新）。
 */
public interface PawCoinWalletRepository extends JpaRepository<PawCoinWallet, Long> {

    Optional<PawCoinWallet> findByUserId(long userId);

    /**
     * 原子加/减余额。{@code delta} 为正=充值、为负=扣减；{@code WHERE balance + :delta >= 0} 守非负。
     * 返回受影响行数：1=成功，0=余额不足或钱包不存在（调用方据此拒绝）。同时手动 bump {@code version}
     * 保持乐观锁字段一致。
     */
    @Modifying
    @Query("update PawCoinWallet w set w.balance = w.balance + :delta, w.version = w.version + 1, "
            + "w.updatedAt = CURRENT_TIMESTAMP where w.userId = :userId and w.balance + :delta >= 0")
    int applyDelta(@Param("userId") long userId, @Param("delta") long delta);

    /**
     * 首次充值前幂等建钱包（并发安全）：{@code ON CONFLICT DO NOTHING} 靠 {@code uq_pawcoin_wallets_user}
     * 兜住并发建，返回 1=新建 / 0=已存在。
     */
    @Modifying
    @Query(value = "INSERT INTO pawcoin_wallets (user_id, balance, version, updated_at) "
            + "VALUES (:userId, 0, 0, now()) ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId);
}
