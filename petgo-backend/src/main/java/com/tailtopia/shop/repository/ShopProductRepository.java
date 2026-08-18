package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 商品仓储（Story 1.1）。本 Story 只读——写入属 Story 1.3 后台录入。 */
public interface ShopProductRepository extends JpaRepository<ShopProduct, Long> {

    /** 对外一律按 token 寻址，绝不按自增 id（CLAUDE.md 护栏）。 */
    Optional<ShopProduct> findByPublicTokenAndActiveTrue(String publicToken);

    /** 稳定排序：运营权重降序，id 降序兜底（避免同权重时分页漂移）。 */
    List<ShopProduct> findByActiveTrueOrderBySortWeightDescIdDesc();

    List<ShopProduct> findByActiveTrueAndCategoryOrderBySortWeightDescIdDesc(ProductCategory category);
}
