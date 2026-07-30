package com.tailtopia.config.repository;

import com.tailtopia.config.domain.PricingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** 定价配置仓储（Story 9.2）。仅 id=1 单行。 */
public interface PricingConfigRepository extends JpaRepository<PricingConfig, Long> {
}
