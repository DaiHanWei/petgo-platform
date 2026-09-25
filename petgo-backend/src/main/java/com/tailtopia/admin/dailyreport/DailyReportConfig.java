package com.tailtopia.admin.dailyreport;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 日报装配入口。🔴 没有 {@code @EnableConfigurationProperties}，{@link DailyReportProperties}
 * 不会被绑定 —— 配了 env 也永远取默认空值，推送看起来上了、实际永远静默，且没有任何报错。
 */
@Configuration
@EnableConfigurationProperties(DailyReportProperties.class)
public class DailyReportConfig {
}
