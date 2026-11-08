package com.tailtopia.shop.coupon.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.dto.ClaimRequest;
import com.tailtopia.shop.coupon.dto.CouponPage;
import com.tailtopia.shop.coupon.dto.CouponSummary;
import com.tailtopia.shop.coupon.dto.CouponView;
import com.tailtopia.shop.coupon.dto.RedeemRequest;
import com.tailtopia.shop.coupon.dto.ShopEntryResponse;
import com.tailtopia.shop.coupon.service.CouponClaimService;
import com.tailtopia.shop.coupon.service.CouponClaimSource;
import com.tailtopia.shop.coupon.service.CouponQueryService;
import com.tailtopia.shop.coupon.service.CouponRedeemService;
import com.tailtopia.shop.coupon.service.NewbieCouponService;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧券接口（v1.4.2 shop Story 2.2 AC2 / AC3 / AC4 / AC8）。
 *
 * <p>🔒 全部在 {@code /api/v1/me} 下且 {@code SecurityConfig} 显式 {@code hasRole("USER")}
 * （{@code /api/v1/me/coupons*} 与 {@code /api/v1/me/shop/**}）：兽医 token 的 sub 与 users.id 撞号，
 * 落到 {@code anyRequest().authenticated()} 会以同号用户名义领券 / 兑换 / 读券（AC10）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeCouponController {

    private final CouponClaimService claims;
    private final CouponRedeemService redeems;
    private final NewbieCouponService newbie;
    private final CouponQueryService query;

    public MeCouponController(CouponClaimService claims, CouponRedeemService redeems,
            NewbieCouponService newbie, CouponQueryService query) {
        this.claims = claims;
        this.redeems = redeems;
        this.newbie = newbie;
        this.query = query;
    }

    /** 领券（首页 / 详情 / banner）。 */
    @PostMapping("/coupons/claim")
    public CouponView claim(@AuthenticationPrincipal Jwt jwt, @RequestBody ClaimRequest req) {
        long userId = currentUserId(jwt);
        CouponClaimSource source = CouponClaimSource.parse(req == null ? null : req.source());
        return claims.claim(userId, req.couponToken(), source, Instant.now());
    }

    /** 兑换码。🔴 码明文不进日志。 */
    @PostMapping("/coupons/redeem")
    public CouponView redeem(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) RedeemRequest req) {
        return redeems.redeem(currentUserId(jwt), req == null ? null : req.code(), Instant.now());
    }

    /** 我的券：{@code tab=available|used}（默认 available），游标分页每页 20。 */
    @GetMapping("/coupons")
    public CouponPage myCoupons(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String cursor) {
        return query.myCoupons(currentUserId(jwt), CouponQueryService.Tab.parse(tab), cursor, Instant.now());
    }

    /** 可用券张数（9.1 订单列表「Kupon Saya · {n} bisa dipakai」入口）。 */
    @GetMapping("/coupons/summary")
    public CouponSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return new CouponSummary(query.availableCount(currentUserId(jwt), Instant.now()));
    }

    /** 进入 Toko（App 每次进入调，幂等）：满足新人条件则发新人券。 */
    @PostMapping("/shop/entry")
    public ShopEntryResponse entry(@AuthenticationPrincipal Jwt jwt) {
        return new ShopEntryResponse(newbie.onEntry(currentUserId(jwt), Instant.now()));
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
