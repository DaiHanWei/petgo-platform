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
}
