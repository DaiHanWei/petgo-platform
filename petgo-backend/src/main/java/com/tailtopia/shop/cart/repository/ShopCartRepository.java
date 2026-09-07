package com.tailtopia.shop.cart.repository;

import com.tailtopia.shop.cart.domain.ShopCart;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 购物车仓储（Story 3.1）。 */
public interface ShopCartRepository extends JpaRepository<ShopCart, Long> {
    Optional<ShopCart> findByUserId(long userId);
}
