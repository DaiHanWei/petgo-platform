package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.PawCoinTransaction;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PawCoin 流水仓储（Story 1.2）。{@link #findByUserIdOrderByCreatedAtDesc} 供余额/流水页（1.4）游标分页读。
 */
public interface PawCoinTransactionRepository extends JpaRepository<PawCoinTransaction, Long> {

    List<PawCoinTransaction> findByUserIdOrderByCreatedAtDesc(long userId, Pageable pageable);

    /** 游标分页：某用户早于 {@code before} 的流水，createdAt 倒序（供 1.4 加载更多）。 */
    List<PawCoinTransaction> findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
            long userId, Instant before, Pageable pageable);
}
