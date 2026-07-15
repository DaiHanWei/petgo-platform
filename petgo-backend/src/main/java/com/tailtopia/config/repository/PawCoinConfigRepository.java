package com.tailtopia.config.repository;

import com.tailtopia.config.domain.PawCoinConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** PawCoin 配置仓储（Story 9.2）。仅 id=1 单行。 */
public interface PawCoinConfigRepository extends JpaRepository<PawCoinConfig, Long> {
}
