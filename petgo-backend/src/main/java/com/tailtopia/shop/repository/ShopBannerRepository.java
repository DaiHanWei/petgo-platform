package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.ShopBanner;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Toko 顶部 banner 仓储（2026-08-27）。 */
public interface ShopBannerRepository extends JpaRepository<ShopBanner, Long> {

    /**
     * 当前该展示的那一张：已上架 + 权重最高；同权重取后建的。
     *
     * <p>🔴 <b>返回单个而不是列表</b> —— 产品口径是「同一时间只展示一张」。
     * 让它返回列表再由调用方取 first，等于把这条口径散到每个调用点上，
     * 早晚有一处忘了取 first 而把全部 banner 都渲染出来。
     *
     * <p>走部分索引 {@code ix_shop_banners_active_pick}（只覆盖 active 行）。
     */
    Optional<ShopBanner> findFirstByActiveTrueOrderBySortWeightDescIdDesc();

    /** 后台列表：全部 banner（含已下架），按取用顺序排列，运营一眼看出「哪张会生效」。 */
    List<ShopBanner> findAllByOrderBySortWeightDescIdDesc();
}
