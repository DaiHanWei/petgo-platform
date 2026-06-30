package com.tailtopia.admin.moderation.repository;

import com.tailtopia.admin.moderation.domain.AdminSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** 单行系统配置仓储（Story 4.3）。仅 id=1 一行（V40 种子插入）。 */
public interface AdminSettingsRepository extends JpaRepository<AdminSettings, Long> {
}
