package com.tailtopia.growth.repository;

import com.tailtopia.growth.domain.GrowthSpendTier;
import org.springframework.data.jpa.repository.JpaRepository;

/** 消费返档位仓储（V1.4.0 ① Story 1.3）。 */
public interface GrowthSpendTierRepository extends JpaRepository<GrowthSpendTier, String> {
}
