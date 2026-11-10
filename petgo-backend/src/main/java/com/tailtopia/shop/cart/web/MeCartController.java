package com.tailtopia.shop.cart.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartEstimateService;
import com.tailtopia.shop.cart.service.CartService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车（Story 3.1，FR-96）。
 *
 * <p>🔴 路径 <b>{@code /api/v1/me/cart}</b>（决策 C1，不用 {@code /users/me}）。
 * <p>🔒 <b>全部要求登录</b> —— {@code /me} 前缀本就在 {@code SecurityConfig} 的受保护范围，
 * 本 Story <b>不需要也不得</b>为它开放行规则。游客加购会拿到 401，这正是 FR-96 要的。
 */
@RestController
@RequestMapping("/api/v1/me/cart")
public class MeCartController {

    private final CartService cart;
    private final CartEstimateService estimates;

    public MeCartController(CartService cart, CartEstimateService estimates) {
        this.cart = cart;
        this.estimates = estimates;
    }

    /**
     * v1.4.2 shop Story 3.1 AC3：每个返回 {@link CartView} 的出口在<b>返回前</b>装配 {@code estimate}。
     * 购物车写事务此时已提交，询价在事务外（P-7）。{@code regionKecamatanId} 可选：
     * 无默认地址时用它（App 传详情页选过的地区，区域参考数据 id 允许外露）。
     */
    private CartView withEstimate(long userId, CartView v, Long regionKecamatanId) {
        return v.withEstimate(estimates.estimate(userId, v, regionKecamatanId));
    }

    @GetMapping
    public CartView view(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long regionKecamatanId) {
        long userId = currentUserId(jwt);
        return withEstimate(userId, cart.view(userId), regionKecamatanId);
    }

    /**
     * 加购。
     *
     * <p>🔴 {@code entrySource} / {@code triggerType} 是<b>归因链的起点</b>（Story 3.10）：
     * 商品「从哪个入口进的购物车」只有此刻知道，下单时抄到订单行，
     * 后台据此算触发卡转化率（AB-13B 判定 A-16）。
     * 两者<b>可选</b>：拿不到就留空，写 NULL 是诚实的「未知」，编一个值会污染看板且事后无法识别。
     */
    @PostMapping("/items")
    public CartView add(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String skuToken, @RequestParam(defaultValue = "1") int qty,
            @RequestParam(required = false) String entrySource,
            @RequestParam(required = false) String triggerType,
            // 立即购买（code review #4）：只勾这一件、车内其它取消勾选。可选，旧版 App 不传 = 普通加购
            @RequestParam(defaultValue = "false") boolean buyNow,
            @RequestParam(required = false) Long regionKecamatanId) {
        long userId = currentUserId(jwt);
        return withEstimate(userId,
                cart.add(userId, skuToken, qty, entrySource, triggerType, buyNow), regionKecamatanId);
    }

    @PutMapping("/items/{skuToken}")
    public CartView setQty(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String skuToken, @RequestParam int qty,
            @RequestParam(required = false) Long regionKecamatanId) {
        long userId = currentUserId(jwt);
        return withEstimate(userId, cart.setQty(userId, skuToken, qty), regionKecamatanId);
    }

    @DeleteMapping("/items/{skuToken}")
    public CartView remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String skuToken,
            @RequestParam(required = false) Long regionKecamatanId) {
        long userId = currentUserId(jwt);
        return withEstimate(userId, cart.remove(userId, skuToken), regionKecamatanId);
    }

    /**
     * 勾选 / 取消勾选单行（Story 4-1，SHOP-FR-04）。
     *
     * <p>🔴 <b>沿用本 Controller 零请求体的风格</b>（AD-S6 明写「沿用」）：
     * 五个既有端点全是 query param + PathVariable，没有一个 {@code @RequestBody}。
     * 为一个布尔值新建 {@code CartSelectionRequest} 会让这里成为唯一的例外，
     * 而例外本身就是下一个人照着抄错的起点。
     *
     * <p>路由与 {@code PUT /items/{skuToken}} 不冲突：那个是两段，这个是三段。
     */
    @PutMapping("/items/{skuToken}/selected")
    public CartView setSelected(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String skuToken, @RequestParam boolean selected,
            @RequestParam(required = false) Long regionKecamatanId) {
        long userId = currentUserId(jwt);
        return withEstimate(userId, cart.setSelected(userId, skuToken, selected), regionKecamatanId);
    }

    /**
     * 全选 / 全不选（Story 4-1，SHOP-FR-04）。
     *
     * <p>v1.4.2 Story 3.1：全选只勾有效行，失效行不动；全不选作用于全部行。
     */
    @PutMapping("/selection")
    public CartView setAllSelected(@AuthenticationPrincipal Jwt jwt,
            @RequestParam boolean selected,
            @RequestParam(required = false) Long regionKecamatanId) {
        long userId = currentUserId(jwt);
        return withEstimate(userId, cart.setAllSelected(userId, selected), regionKecamatanId);
    }

    /** 清空全部失效商品（已下架 / 已售罄）。 */
    @DeleteMapping("/invalid-items")
    public CartView clearInvalid(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long regionKecamatanId) {
        long userId = currentUserId(jwt);
        return withEstimate(userId, cart.clearInvalid(userId), regionKecamatanId);
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
