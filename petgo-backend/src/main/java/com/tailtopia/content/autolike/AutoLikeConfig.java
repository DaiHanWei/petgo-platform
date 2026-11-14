package com.tailtopia.content.autolike;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 自动点赞配置挂载（V1.5.0）：注册 {@link AutoLikeProperties}。 */
@Configuration
@EnableConfigurationProperties(AutoLikeProperties.class)
public class AutoLikeConfig {
}
