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

    /**
     * 后台商品列表一页（V1.3.0 Story 10.3 AC1：每页 20）。
     *
     * <p>⚠️ 重构前是 {@code findAll()} 把整张表拉回内存再过滤排序 —— SKU ≤ 30 的现在没事，
     * 但那不是「分页」，翻页器也没法从它身上长出来。
     *
     * <p>排序与 App 端列表<b>逐字一致</b>（权重降序 + id 降序兜底）：换一套排序，
     * 运营调过的权重在后台看到的顺序就跟 App 对不上了。
     *
     * <p>{@code countQuery} 显式给出（派生的 count 会把 ORDER BY 带上，有的方言下报错）。
     */
    @Query(value = "select p from ShopProduct p "
            + "where (:category is null or p.category = :category) "
            + "  and (:active is null or p.active = :active) "
            + "order by p.sortWeight desc, p.id desc",
            countQuery = "select count(p) from ShopProduct p "
            + "where (:category is null or p.category = :category) "
            + "  and (:active is null or p.active = :active)")
    org.springframework.data.domain.Page<ShopProduct> adminSearch(
            @Param("category") ProductCategory category, @Param("active") Boolean active,
            org.springframework.data.domain.Pageable pageable);

    /** 后台摘要条（AC1）：上架 / 下架商品数一次问出来。 */
    long countByActive(boolean active);

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
