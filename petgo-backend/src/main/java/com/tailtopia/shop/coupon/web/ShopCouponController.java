package com.tailtopia.shop.coupon.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.dto.CouponList;
import com.tailtopia.shop.coupon.dto.NewbieCampaignView;
import com.tailtopia.shop.coupon.service.CouponQueryService;
import com.tailtopia.shop.coupon.service.NewbieCouponService;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toko 券只读接口（v1.4.2 shop Story 2.2 AC5 / AC6 / AC7）。
 *
 * <ul>
 *   <li>{@code GET /shop/coupons/newbie-campaign}：permitAll（游客新人弹窗），无活动或已领完 → 204；</li>
 *   <li>{@code GET /shop/coupons/for-you}：仅 {@code role=USER}（精确路径，不写 {@code /shop/coupons/**} 通配）；</li>
 *   <li>{@code GET /shop/products/{token}/coupons}：落在商品 GET permitAll 规则下，登录用户只认 {@code role=USER}
 *       （同 {@code ContentFeedController.userViewerId}），其余按游客处理。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/shop")
public class ShopCouponController {

    private final NewbieCouponService newbie;
    private final CouponQueryService query;

    public ShopCouponController(NewbieCouponService newbie, CouponQueryService query) {
        this.newbie = newbie;
        this.query = query;
    }

    @GetMapping("/coupons/newbie-campaign")
    public ResponseEntity<NewbieCampaignView> newbieCampaign() {
        return newbie.guestCampaign(Instant.now())
                .map(c -> ResponseEntity.ok(new NewbieCampaignView(c.getPublicToken(), c.getKind().name(),
                        c.getAmount(), c.getMinSpend(), c.getNameId(), c.getNameEn())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/coupons/for-you")
    public CouponList forYou(@AuthenticationPrincipal Jwt jwt) {
        Long userId = userViewerId(jwt);
        if (userId == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        return new CouponList(query.forYou(userId, Instant.now()));
    }

    @GetMapping("/products/{token}/coupons")
    public CouponList productCoupons(@PathVariable String token, @AuthenticationPrincipal Jwt jwt) {
        return new CouponList(query.forProduct(token, userViewerId(jwt), Instant.now()));
    }

    /** 只认 {@code role=USER} 的 JWT（兽医 / 运营 token 的 sub 不是 users.id，按游客处理）；无效 → null。 */
    public static Long userViewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
