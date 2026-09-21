package com.tailtopia.admin.dashboard.config;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 看板跑批配置（V1.3.0 Story 3.3 AC5）：{@code petgo.admin.dashboard.*}，均有默认值、可 env 覆盖
 * （{@code ADMIN_DASHBOARD_CRON} / {@code ADMIN_DASHBOARD_BACKFILL_FROM}）。
 *
 * @param cron         物化跑批 cron（WIB 时区解释，默认每天 01:00 WIB，此时「昨天」已完整）。
 *                     运行时以 {@code @Scheduled} 的占位符 {@code ${petgo.admin.dashboard.cron}} 为准（同一配置项），
 *                     本字段仅供配置校验 / 测试与运维查看，不要在别处再读它另起一套调度
 * @param backfillFrom D-15 全量回填起点（默认 2026-07-17 = 参考 SQL 创建日）
 */
@ConfigurationProperties(prefix = "petgo.admin.dashboard")
public record DashboardProperties(@DefaultValue("0 0 1 * * *") String cron,
        @DefaultValue("2026-07-17") LocalDate backfillFrom) {
}
