package com.tailtopia.config.repository;

import com.tailtopia.config.domain.AppVersionConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** App 版本配置仓储（V1.3.4 Story 3.1）。仅 id=1 单行。 */
public interface AppVersionConfigRepository extends JpaRepository<AppVersionConfig, Long> {
}
