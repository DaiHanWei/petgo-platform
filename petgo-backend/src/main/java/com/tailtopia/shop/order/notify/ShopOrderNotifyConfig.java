package com.tailtopia.shop.order.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 新订单 Lark 提醒的装配入口（Story 3-4）。
 *
 * <p>🔴 没有这个 {@code @EnableConfigurationProperties}，{@link ShopOrderNotifyProperties}
 * 不会被绑定 —— 表现是所有值都取 Java 侧默认（{@code mode=off}），配了 env 也不生效，
 * 而且**没有任何报错**：功能看起来上了，实际永远静默。
 */
@Configuration
@EnableConfigurationProperties(ShopOrderNotifyProperties.class)
public class ShopOrderNotifyConfig {
}
