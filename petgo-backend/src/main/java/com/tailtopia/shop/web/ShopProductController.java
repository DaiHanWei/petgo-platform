package com.tailtopia.shop.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.dto.ShopProductDetailView;
import com.tailtopia.shop.dto.ShopProductSummaryView;
import com.tailtopia.shop.service.ShopProductQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自营商品只读端点（Story 1.1，FR-94 / FR-94A / FR-93A）。
 *
 * <p>🔴 <b>两个 GET 对游客放行</b>（{@code SecurityConfig}）——FR-93A：Toko 允许未登录浏览，
 * 与 V1.1.2 FR-78「未登录点击非落地 Tab 触发登录引导」的既有机制<b>有意不同</b>：
 * 商品浏览是转化漏斗最上层，用登录墙拦截会直接杀掉转化；登录引导推迟到<b>加入购物车</b>
 * （属 Story 3.6）。
 *
 * <p>🔴 <b>路径参数是 {@code publicToken} 不是自增 id</b>（CLAUDE.md 护栏）；
 * 未知 token → <b>404 而非 403</b>，防枚举探测（与 {@code HealthRecordController} 同范式）。
 *
 * <p><b>只读</b>：本 Story 不提供任何 POST/PATCH/DELETE——写入属 Story 1.3 后台。
 */
@RestController
@RequestMapping("/api/v1/shop/products")
public class ShopProductController {

    private final ShopProductQueryService query;

    public ShopProductController(ShopProductQueryService query) {
        this.query = query;
    }

    /**
     * 商品列表（FR-93 区域③④）。
     *
     * @param category 可选品类筛选；非法值 → 422（{@code AppException.validation}），不静默忽略
     */
    @GetMapping
    public List<ShopProductSummaryView> list(@RequestParam(required = false) String category) {
        return query.list(parseCategory(category));
    }

    /** 商品详情 + 其 SKU 列表（FR-94 / FR-94A）。未上架或不存在 → 404。 */
    @GetMapping("/{token}")
    public ShopProductDetailView detail(@PathVariable String token) {
        return query.detail(token);
    }

    private static ProductCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ProductCategory.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // 静默忽略非法筛选值会让前端拿到「全部商品」却以为筛过了——明确报错
            throw AppException.validation("商品品类非法");
        }
    }
}
