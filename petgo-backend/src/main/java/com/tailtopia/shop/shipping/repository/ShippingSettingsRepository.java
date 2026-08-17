package com.tailtopia.shop.shipping.repository;

import com.tailtopia.shop.shipping.domain.ShippingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** 全局配送设置仓储（单例行）。 */
public interface ShippingSettingsRepository extends JpaRepository<ShippingSettings, Short> {
}
