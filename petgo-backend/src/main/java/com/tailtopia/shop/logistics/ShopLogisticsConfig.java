package com.tailtopia.shop.logistics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** v1.4.2 shop Story 1.1：绑定 {@link ShippingProbeProperties}。 */
@Configuration
@EnableConfigurationProperties(ShippingProbeProperties.class)
public class ShopLogisticsConfig {
}
