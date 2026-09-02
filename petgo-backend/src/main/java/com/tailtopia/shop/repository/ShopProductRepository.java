package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 商品仓储（Story 1.1）。本 Story 只读——写入属 Story 1.3 后台录入。 */
public interface ShopProductRepository extends JpaRepository<ShopProduct, Long> {

    /** 对外一律按 token 寻址，绝不按自增 id（CLAUDE.md 护栏）。 */
    Optional<ShopProduct> findByPublicTokenAndActiveTrue(String publicToken);

    /** 稳定排序：运营权重降序，id 降序兜底（避免同权重时分页漂移）。 */
    List<ShopProduct> findByActiveTrueOrderBySortWeightDescIdDesc();

    List<ShopProduct> findByActiveTrueAndCategoryOrderBySortWeightDescIdDesc(ProductCategory category);

    // ---------- 关键词搜索（2026-08-31）----------
    // 🔴 搜 name + brand 两列：运营录入时品牌常不含在商品名里（如 name="Adult Dog Kibble"、
    //    brand="Royal Canin"），只搜 name 会让「royal」这种最自然的输入零结果。
    //
    // 🔴 排序与列表**逐字一致**（权重降序 + id 降序）：搜索结果换一套排序，
    //    运营调过的权重在搜索里就失效了，同一件商品在两个入口的位置对不上。
    //
    // ⚠️ 调用方必须传**已转小写、已转义、已加 %% 的整串 pattern**——
    //    转义（\ % _）留在服务层做，仓储不猜输入形态。escape 字符显式声明为反斜杠，
    //    否则用户输入一个 `%` 就等于「匹配全部」。

    @Query("select p from ShopProduct p where p.active = true "
            + "and (lower(p.name) like :pattern escape '\\' "
            + "  or lower(p.brand) like :pattern escape '\\') "
            + "order by p.sortWeight desc, p.id desc")
    List<ShopProduct> searchActive(@Param("pattern") String pattern);

    @Query("select p from ShopProduct p where p.active = true and p.category = :category "
            + "and (lower(p.name) like :pattern escape '\\' "
            + "  or lower(p.brand) like :pattern escape '\\') "
            + "order by p.sortWeight desc, p.id desc")
    List<ShopProduct> searchActiveByCategory(@Param("pattern") String pattern,
            @Param("category") ProductCategory category);
}
