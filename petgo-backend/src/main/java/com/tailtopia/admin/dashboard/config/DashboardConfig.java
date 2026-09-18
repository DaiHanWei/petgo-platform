package com.tailtopia.admin.dashboard.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 装配 {@link DashboardProperties}（照 {@code ConsultConfig} / {@code PayConfig} 范式）。 */
@Configuration
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardConfig {
}
