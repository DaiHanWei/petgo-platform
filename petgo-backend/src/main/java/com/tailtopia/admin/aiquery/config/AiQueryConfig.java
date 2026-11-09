package com.tailtopia.admin.aiquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 绑定 {@link AiQueryProperties}（照 {@code ConsultConfig} 「每模块一个 Config 注册其 Properties」范式）。 */
@Configuration
@EnableConfigurationProperties(AiQueryProperties.class)
public class AiQueryConfig {
}
