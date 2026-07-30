package com.tailtopia.config.repository;

import com.tailtopia.config.domain.ConfigChangeLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 配置变更日志仓储（Story 9.2，append-only）。 */
public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long> {

    List<ConfigChangeLog> findTop100ByOrderByChangedAtDesc();
}
