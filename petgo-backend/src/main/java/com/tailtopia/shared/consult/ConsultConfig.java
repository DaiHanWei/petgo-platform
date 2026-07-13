package com.tailtopia.shared.consult;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 兽医咨询计费配置装配（Story 3.4）。绑定 {@link ConsultProperties}（前缀 {@code petgo.consult}），
 * 照既有 {@code TriageConfig}/{@code PayConfig}/{@code ImConfig} 的「每模块一个 Config 注册其 Properties」范式。
 *
 * <p>没有这个 {@code @EnableConfigurationProperties}，{@code ConsultProperties} 不会被 Spring 装配、
 * env {@code CONSULT_UNIT_PRICE}/{@code CONSULT_VET_SHARE_RATE} 静默失效（恒为字段默认值）。
 */
@Configuration
@EnableConfigurationProperties(ConsultProperties.class)
public class ConsultConfig {
}
