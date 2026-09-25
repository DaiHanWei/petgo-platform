package com.tailtopia.shop.cart.repository;

import com.tailtopia.shop.cart.domain.ShopCartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 购物车行仓储（Story 3.1）。 */
public interface ShopCartItemRepository extends JpaRepository<ShopCartItem, Long> {

    List<ShopCartItem> findByCartIdOrderByIdAsc(long cartId);

    Optional<ShopCartItem> findByCartIdAndSkuId(long cartId, long skuId);

    void deleteByCartIdAndSkuIdIn(long cartId, List<Long> skuIds);

    /** 账号注销级联（Story 7.3）：整车清空后车行本身也随之删除。 */
    void deleteByCartId(long cartId);

    /**
     * 单行勾选 / 取消勾选（Story 4-1）。
     *
     * <p>🔴 <b>条件写而不是「读全部 → 循环 save」</b>：勾选是高频轻操作，
     * 把整车读进内存再逐行保存，会让「点一下勾选框」变成 N 次 UPDATE。
     *
     * @return 受影响行数（0 = 该 SKU 不在这辆车里）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ShopCartItem i set i.selected = :selected, i.updatedAt = CURRENT_TIMESTAMP "
            + "where i.cartId = :cartId and i.skuId = :skuId")
    int updateSelected(@Param("cartId") long cartId, @Param("skuId") long skuId,
            @Param("selected") boolean selected);

    /**
     * 全选 / 全不选（Story 4-1 AC2）。
     *
     * <p>🔴 <b>作用于车内全部行，含失效行</b>：失效行也带着 {@code selected} 照实下发，
     * 只是永不计入选中合计。把它们排除在「全选」之外，会让用户在商品补货后
     * 发现自己「全选」过的东西没被选上。
     *
     * @return 受影响行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ShopCartItem i set i.selected = :selected, i.updatedAt = CURRENT_TIMESTAMP "
            + "where i.cartId = :cartId")
    int updateAllSelected(@Param("cartId") long cartId, @Param("selected") boolean selected);
}
