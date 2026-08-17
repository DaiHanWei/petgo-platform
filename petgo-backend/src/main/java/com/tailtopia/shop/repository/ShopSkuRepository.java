package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.ShopSku;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** SKU 仓储（Story 1.1）。本 Story 只读。 */
public interface ShopSkuRepository extends JpaRepository<ShopSku, Long> {

    List<ShopSku> findByProductIdOrderByIdAsc(Long productId);

    List<ShopSku> findByProductIdInOrderByIdAsc(List<Long> productIds);
}
