package com.tailtopia.shop.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.shop.coupon.web.ShopCouponController;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.dto.ShopProductDetailView;
import com.tailtopia.shop.dto.ShopProductSummaryView;
import com.tailtopia.shop.logistics.ShippingEstimateService;
import com.tailtopia.shop.logistics.ShippingEstimateView;
import com.tailtopia.shop.service.ShopProductQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private final ShippingEstimateService estimates;
    private final RedisRateLimiter rateLimiter;

    /** 运费估算限流：每 viewer（或 IP）每分钟 30 次（防刷 Biteship 计费询价）。 */
    static final int ESTIMATE_LIMIT = 30;
    static final Duration ESTIMATE_WINDOW = Duration.ofMinutes(1);

    public ShopProductController(ShopProductQueryService query, ShippingEstimateService estimates,
            RedisRateLimiter rateLimiter) {
        this.query = query;
        this.estimates = estimates;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 商品列表（FR-93 区域③④）+ 关键词搜索（2026-08-31）。
     *
     * <p>🔴 搜索**挂在列表接口上而不是另开 /search**：两者返回同一个 DTO、同一套排序，
     * 且 {@code q} 与 {@code category} 需要组合生效。拆成两个端点会立刻带来
     * 「搜索里要不要也支持品类」这种必须两边同步维护的重复。
     *
     * <p>🔴 <b>两种返回形态</b>（Story 4-5）：
     * <ul>
     *   <li><b>不带任何分页参数</b> → 原来的全量 JSON <b>数组</b>（老版本 App 期望的形态）；</li>
     *   <li>带 {@code cursor} 或 {@code size} → {@code {items, nextCursor, hasMore}} 信封。</li>
     * </ul>
     * 判据是「有没有传分页参数」而不是「User-Agent 是什么」—— 后者既不可靠也没必要。
     *
     * @param category 可选品类筛选；非法值 → 422（{@code AppException.validation}），不静默忽略
     * @param q        可选关键词，命中 name 或 brand（忽略大小写）；空白等同于不传
     * @param cursor   可选游标；传了即进入分页形态。空白串同样进入分页形态（= 请求第一页）
     * @param size     可选页长；默认 20，上限 100
     * @param jwt      可选（v1.4.2 shop Story 2.2 AC9）：只认 {@code role=USER}，用于 {@code promo} 计入本人可用券；
     *                 游客 / 非 USER 角色 → 只算 CLAIM 模板。两种形态都带 {@code promo}。
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal Jwt jwt) {
        ProductCategory parsed = parseCategory(category);
        Long viewer = ShopCouponController.userViewerId(jwt);
        if (cursor == null && size == null) {
            // 🔴🔴 老版本兼容分支（SHOP-NFR-04）—— 不带任何分页参数时返回**原来的全量数组**。
            //
            // 线上已发布的 App 版本期望这个端点返回一个 JSON **数组**。直接改成分页信封，
            // 它们会解析失败：轻则只看到第一页，重则整页打不开。
            // **发版救不了已经装在用户手机上的那些**。
            //
            // ⚠️ 不要「顺手」把这条改成「返回只有一页的信封」—— 那不是兼容，
            //    那是换一种方式打破同一个契约。
            //
            // 🔴 **删除条件**：本分支保留到「最低支持版本」升级到含分页的版本为止。
            //    删之前必须先确认**线上版本分布**（不是看发版记录，是看实际活跃版本），
            //    不能因为「新版本已经发了一段时间」就推定老版本没人用了。
            return ResponseEntity.ok(query.list(parsed, q, viewer));
        }
        return ResponseEntity.ok(query.page(parsed, q, cursor, size, viewer));
    }

    /** 商品详情 + 其 SKU 列表（FR-94 / FR-94A）。未上架或不存在 → 404。 */
    @GetMapping("/{token}")
    public ShopProductDetailView detail(@PathVariable String token) {
        return query.detail(token);
    }

    /**
     * 运费估算（v1.4.2 shop Story 3.1 AC2，D-9）：「运费 Rp X 起」+ 能否送达 + 免运进度。
     *
     * <p>🔒 路径在既有 {@code GET /api/v1/shop/products/**} permitAll 下，<b>不新增安全规则</b>；
     * 可选 viewer <b>只认 role=USER</b>（兽医 token 的 sub 与 users.id 撞号，不能当用户读其地址）——
     * 非 USER 带 {@code addressToken} 一律忽略，按游客处理。
     * Biteship 失败 → 200 + {@code quoteFailed=true}（不抛 503）。
     */
    @GetMapping("/{token}/shipping-estimate")
    public ShippingEstimateView shippingEstimate(@PathVariable String token,
            @RequestParam(required = false) String skuToken,
            @RequestParam(required = false) Integer qty,
            @RequestParam(required = false) String addressToken,
            @RequestParam(required = false) Long regionKecamatanId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        Long viewer = ShopCouponController.userViewerId(jwt);
        // 🔒 限流键只含 userId / 客户端 IP，不含地址与邮编
        rateLimiter.check("rl:shop:ship-estimate:" + (viewer != null ? "u" + viewer : "ip" + clientIp(http)),
                ESTIMATE_LIMIT, ESTIMATE_WINDOW);
        return estimates.estimate(token, skuToken, qty, viewer,
                viewer == null ? null : addressToken, regionKecamatanId);
    }

    /** 取客户端 IP（照 {@code AuthController.clientIp} 先例：X-Forwarded-For 首段，否则 remoteAddr）。 */
    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return http.getRemoteAddr();
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
