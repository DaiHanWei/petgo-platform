package com.tailtopia.content.larksync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Lark 定时发帖装配入口。没有这个 {@code @EnableConfigurationProperties}，
 * {@link LarkContentSyncProperties} 不会被 Spring 装配、yml 的 {@code petgo.lark-content} 段不生效。
 */
@Configuration
@EnableConfigurationProperties(LarkContentSyncProperties.class)
public class LarkContentSyncConfig {
}
