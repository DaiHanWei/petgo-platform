package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.PawCoinTransaction;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PawCoin 流水仓储（Story 1.2）。{@link #findByUserIdOrderByCreatedAtDesc} 供余额/流水页（1.4）游标分页读。
 */
public interface PawCoinTransactionRepository extends JpaRepository<PawCoinTransaction, Long> {

    List<PawCoinTransaction> findByUserIdOrderByCreatedAtDesc(long userId, Pageable pageable);

    /** 游标分页：某用户早于 {@code before} 的流水，createdAt 倒序（供 1.4 加载更多）。 */
    List<PawCoinTransaction> findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
            long userId, Instant before, Pageable pageable);

    /**
     * 注销级联（Story 1.6）：物理删该用户全部流水。返回删除行数（0=无流水，幂等）。
     * 个人流水随注销删除；对账留痕在 append-only 的 {@code ledger_entries}（不删）。
     */
    @Modifying
    @Query("delete from PawCoinTransaction t where t.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
