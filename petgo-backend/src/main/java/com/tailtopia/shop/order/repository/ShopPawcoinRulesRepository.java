package com.tailtopia.shop.order.repository;

import com.tailtopia.shop.order.domain.ShopPawcoinRules;
import org.springframework.data.jpa.repository.JpaRepository;

/** PawCoin 电商规则仓储（单例行）。 */
public interface ShopPawcoinRulesRepository extends JpaRepository<ShopPawcoinRules, Short> {
}
