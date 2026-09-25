package com.tailtopia.config.repository;

import com.tailtopia.config.domain.PawCoinTopupTier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** PawCoin 充值档位仓储（Story 9.2）。 */
public interface PawCoinTopupTierRepository extends JpaRepository<PawCoinTopupTier, Long> {

    List<PawCoinTopupTier> findAllByOrderBySortOrderAsc();

    List<PawCoinTopupTier> findByEnabledTrueOrderBySortOrderAsc();

    Optional<PawCoinTopupTier> findByTierKey(String tierKey);

    long countByEnabledTrue();

    // ── V1.3.0 Story 6.2：只显启用中 / 查看已停用 / 新建档位 ──
    List<PawCoinTopupTier> findByEnabledFalseOrderBySortOrderAsc();

    boolean existsByAmountIdr(long amountIdr);

    long countByEnabledFalse();

    List<PawCoinTopupTier> findAllByOrderByAmountIdrAsc();
}
