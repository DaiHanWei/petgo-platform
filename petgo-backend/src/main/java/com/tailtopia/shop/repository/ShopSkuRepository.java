package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.ShopSku;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** SKU 仓储（Story 1.1）。 */
public interface ShopSkuRepository extends JpaRepository<ShopSku, Long> {

    List<ShopSku> findByProductIdOrderByIdAsc(Long productId);

    List<ShopSku> findByProductIdInOrderByIdAsc(List<Long> productIds);

    long countByProductId(long productId);

    /**
     * 在售 SKU 总数（Story 1.5 / AB-10D 的 C-7 上限判定）。
     *
     * <p>🔴 <b>「在售商品（{@code is_active = true}）的 SKU 总数」的定义只存在于这一处。</b>
     * 后台顶部告警条与「阻止上架」两处都走它——各写一遍必然漂移（一处算在售 SKU、一处算商品数），
     * 表现是「明明报警了却还能上架」，而两边各自的测试都会是绿的。
     *
     * <p>单条 count 子查询，不加载任何实体。
     */
    @Query("select count(s) from ShopSku s "
            + "where s.productId in (select p.id from ShopProduct p where p.active = true)")
    long countActiveSkus();
}
