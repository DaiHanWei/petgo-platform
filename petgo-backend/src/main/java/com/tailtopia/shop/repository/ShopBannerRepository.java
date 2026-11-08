package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.ShopBanner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Toko 顶部 banner 仓储（2026-08-27）。 */
public interface ShopBannerRepository extends JpaRepository<ShopBanner, Long> {

    /**
     * ⚠️ v1.4.2 Story 6.1 起<b>不再被 App / 后台「生效中」路径调用</b>（它不看排期、不看目标是否有效）；
     * 取用规则单一来源是 {@code ShopBannerQueryService.liveBanners}。方法保留，免得别处静默断。
     *
     * <p>（原注释）当前该展示的那一张：已上架 + 权重最高；同权重取后建的。
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

    /**
     * 轮播候选（v1.4.2 Story 6.1 AC4，D-16）：已上架 + 在排期内（左闭右开），按取用顺序
     * （权重降序、同权重 id 降序）。调用方传 {@code PageRequest.of(0, 30)} 限量；目标有效性由
     * {@code ShopBannerQueryService} 批量判。走部分索引 {@code ix_shop_banners_active_pick}。
     */
    @Query("select b from ShopBanner b where b.active = true "
            + "and (b.startAt is null or b.startAt <= :now) "
            + "and (b.endAt is null or b.endAt > :now) "
            + "order by b.sortWeight desc, b.id desc")
    List<ShopBanner> findLiveCandidates(@Param("now") Instant now, Pageable pageable);
}
