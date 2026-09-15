package com.tailtopia.profile.recommend;

import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「逛别人家的毛孩子」推荐位（V1.3.0 batch-b1 Story 4.1 · FR-121）。
 * {@code GET /api/v1/me/pet-recommendations}
 *
 * <h2>为什么挂在 {@code /me} 下</h2>
 * 推荐池是**按查看者算的**（AC3：互相拉黑的双方不互推），同一时刻给两个人的结果不一样 ——
 * 挂成 {@code /pet-recommendations} 会让它看着像一份全站公共列表。
 * 先例是 {@code /api/v1/me/shop/recommendations}（那条的注释写得更直白：
 * 「在 /me 下是刻意的 —— FR-93 状态矩阵里游客不展示该区」），这里同理。
 *
 * <h2>🔴 需要登录</h2>
 * 不在 {@code SecurityConfig} 里放行，落进默认的 authenticated 规则。
 * <p>AC6 的场景是「养宠但尚未建档」的<b>登录用户</b>；Story 4.2 会扩到另一种无档案状态，
 * 但**游客态一行不动**（story Dev Notes 明写）。所以这里不做游客降级，直接 401。
 *
 * <h2>分页（Story 4.3 · AC3）</h2>
 * {@code ?cursor=} 是 <b>keyset</b> 游标（整个排序键的 base64url 串，见 {@link PetRecommendCursor}）——
 * 客户端原样回传。Story 4.1 / 4.2 的两个推荐位不传它（一屏铺满就够），全屏集合页靠它往下翻。
 * <p>🔴 <b>坏游标当第一页处理，不 400</b>：游标是客户端传回来的，为一个坏串把整页锁死
 * 是把用户关在门外（与 {@code KeysetCursor} 同一条既定口径）。
 */
@RestController
public class PetRecommendationController {

    private final PetRecommendationService recommendations;

    public PetRecommendationController(PetRecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/api/v1/me/pet-recommendations")
    public RecommendedPetResponse.Page recommendations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        long viewerId = currentUserId(jwt);
        int size = limit == null ? PetRecommendationService.DEFAULT_LIMIT : limit;
        return recommendations.pageFor(viewerId, size,
                PetRecommendCursor.decodeOrNull(cursor), Instant.now());
    }

    /**
     * 当前登录<b>用户</b> id。
     *
     * <p>⚠️ 只认 {@code role=USER}：兽医 token 的 {@code sub=vetId} 与 {@code users.id}
     * 是两个会碰撞的命名空间 —— 拿它当 viewerId 去判拉黑，排除掉的是某个无关用户的关系。
     */
    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null
                || !"USER".equals(jwt.getClaimAsString("role"))) {
            throw AppException.unauthorized("需要登录");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("需要登录");
        }
    }
}
