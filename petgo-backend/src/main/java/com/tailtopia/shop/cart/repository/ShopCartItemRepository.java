package com.tailtopia.shop.cart.repository;

import com.tailtopia.shop.cart.domain.ShopCartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 购物车行仓储（Story 3.1）。 */
public interface ShopCartItemRepository extends JpaRepository<ShopCartItem, Long> {

    List<ShopCartItem> findByCartIdOrderByIdAsc(long cartId);

    Optional<ShopCartItem> findByCartIdAndSkuId(long cartId, long skuId);

    void deleteByCartIdAndSkuIdIn(long cartId, List<Long> skuIds);
}
