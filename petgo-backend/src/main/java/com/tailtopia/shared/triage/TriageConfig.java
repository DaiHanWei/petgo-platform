package com.tailtopia.shared.triage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 问诊配置装配（Story 2.1）。绑定 {@link TriageProperties}（前缀 {@code petgo.triage}），
 * 照既有 {@code ImConfig}/{@code PayConfig}/{@code MediaConfig} 的「每模块一个 Config 注册其 Properties」范式。
 *
 * <p>没有这个 {@code @EnableConfigurationProperties}，{@code TriageProperties} 不会被 Spring 装配、
 * env {@code TRIAGE_DEFAULT_FREE_QUOTA} 静默失效（恒为字段默认值 1）——这是本 story 相对抢跑草稿补的关键接线。
 */
@Configuration
@EnableConfigurationProperties(TriageProperties.class)
public class TriageConfig {
}
