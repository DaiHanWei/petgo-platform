package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上下架与在售 SKU 上限守护（Story 1.5，AB-10D）。
 *
 * <p>🔒 <b>本类刻意不依赖任何库存类型</b>（{@code InventoryService} / {@code SkuInventoryRepository}
 * / {@code InventoryMovementService} 一个都不 import）。这是 <b>AC2 的能力缺席证明</b>：
 * 「下架时不碰已锁定库存」不是靠开发者记得别调，而是<b>这个类根本调不到</b>——
 * 想违反它得先加一个 import，那在评审时是看得见的。
 *
 * <p>SPEC-7 口径（2026-08-17 产品拍板）：<b>下架 = 只改可见性。</b>
 * 已下单未支付的用户照常付款履约；召回场景走决策 S-3 的「AB-11D 手工选单取消」。
 *
 * <p>⚠️ <b>SKU 上限只约束上架方向。</b>下架只会让在售总数变小，任何时候都应允许——
 * 若给下架也加上限校验，会出现「超限了反而下架不掉」的死锁。
 *
 * <p>🔴 <b>2026-08-19 产品决策：默认不设上限</b>（{@code petgo.shop.sku-cap} 默认 0）。
 * 原先默认 30，注释写的是「C-7 的战略边界，不是性能限制」——即刻意限制选品规模。
 * 产品拍板取消该限制，<b>但守护机制整套保留</b>：把配置项设为正数即恢复原行为
 * （告警条、上架拦截、审计文案全都照常）。删掉机制的话，日后想重新设限就得把
 * 「上架之后的总数」这套判定重写一遍，而那正是最容易写歪的地方（见 {@link #list}）。
 */
@Service
public class AdminShopListingService {

    private final ShopProductRepository products;
    private final ShopSkuRepository skus;
    private final AdminAuditService audit;
    private final int skuCap;

    public AdminShopListingService(ShopProductRepository products, ShopSkuRepository skus,
            AdminAuditService audit, @Value("${petgo.shop.sku-cap:0}") int skuCap) {
        this.products = products;
        this.skus = skus;
        this.audit = audit;
        this.skuCap = skuCap;
    }

    /**
     * 在售 SKU 总数 —— <b>告警条与阻止上架共用这一个口径</b>。
     *
     * <p>🔴 计数对象是「<b>在售商品（{@code is_active = true}）的 SKU 总数</b>」，
     * 不是商品数、也不是全部 SKU（后台 PRD §AB-10D 原文）。
     *
     * <p>🔴 <b>不要在别处再写一遍这个查询</b>：两处一旦漂移（一个算在售 SKU、一个算商品数），
     * 表现就是「明明报警了却还能上架」，而两边各自的测试都会是绿的。
     */
    @Transactional(readOnly = true)
    public long activeSkuCount() {
        return skus.countActiveSkus();
    }

    /** 配置上限（{@code petgo.shop.sku-cap}）。<b>0 或负数 = 不限</b>（2026-08-19 起的默认）。 */
    public int skuCap() {
        return skuCap;
    }

    /**
     * 是否启用上限。
     *
     * <p>🔴 <b>「不限」用一个方法表达，不要在各处各写一遍 {@code skuCap > 0}</b> ——
     * 告警条与上架拦截必须同进同退，两处判定一旦漂移就会出现
     * 「报警了却拦不住」或「拦住了却不报警」，而两边各自的测试都会是绿的。
     */
    public boolean capEnabled() {
        return skuCap > 0;
    }

    /** 是否已达上限（供顶部告警条判定）。不限时恒为 false —— 告警条整条不渲染。 */
    @Transactional(readOnly = true)
    public boolean atOrOverCap() {
        return capEnabled() && activeSkuCount() >= skuCap;
    }

    /**
     * 上架。
     *
     * <p>🔴 上限判定看的是<b>上架之后</b>的总数：本商品自己的 SKU 数要一并计入。
     * 只看「当前是否已达 30」会让一个 3 SKU 的商品在 28 时被放行到 31。
     *
     * @throws AppException 上架会使在售 SKU 总数超过上限
     */
    @Transactional
    public ShopProduct list(long productId, long actorAccountId) {
        ShopProduct p = require(productId);
        if (p.isActive()) {
            return p;   // 幂等：已上架再点一次不报错、不重复写审计
        }
        long own = skus.countByProductId(productId);
        long after = activeSkuCount() + own;
        if (capEnabled() && after > skuCap) {
            throw AppException.conflict(
                    "上架会使在售 SKU 总数达到 %d，超过上限 %d。请先下架其他商品，或调整 petgo.shop.sku-cap。"
                            .formatted(after, skuCap))
                    .code("admin.err.product.skuCapExceeded", after, skuCap);
        }
        p.list();
        // 审计文案：不限时不写「/上限」，否则会留下「1234/0」这种读不通的记录。
        audit.record(actorAccountId, AuditActions.SHOP_PRODUCT_LISTED, "SHOP_PRODUCT",
                p.getPublicToken(),
                capEnabled()
                        ? "上架：%s（在售 SKU %d/%d）".formatted(p.getName(), after, skuCap)
                        : "上架：%s（在售 SKU %d，未设上限）".formatted(p.getName(), after));
        return p;
    }

    /**
     * 下架。
     *
     * <p>🔴 <b>不做上限校验、不碰库存、不取消订单、不发通知</b>——见类注释的 SPEC-7 口径。
     */
    @Transactional
    public ShopProduct delist(long productId, long actorAccountId) {
        ShopProduct p = require(productId);
        if (!p.isActive()) {
            return p;   // 幂等
        }
        p.delist();
        audit.record(actorAccountId, AuditActions.SHOP_PRODUCT_DELISTED, "SHOP_PRODUCT",
                p.getPublicToken(), "下架：" + p.getName());
        return p;
    }

    private ShopProduct require(long productId) {
        return products.findById(productId)
                .orElseThrow(() -> AppException.notFound("商品不存在").code("admin.err.product.notFound"));
    }
}
