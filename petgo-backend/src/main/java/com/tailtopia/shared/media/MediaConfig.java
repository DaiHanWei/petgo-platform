package com.tailtopia.shared.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用媒体配置绑定（Story 2.1）。{@link MediaProperties} 前缀 {@code media}，env 注入。 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {
}
