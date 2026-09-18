package com.tailtopia.config.repository;

import com.tailtopia.config.domain.SupportContactConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** 客服联系方式配置仓储（Story 3-1）。仅 id=1 单行。 */
public interface SupportContactConfigRepository extends JpaRepository<SupportContactConfig, Long> {
}
