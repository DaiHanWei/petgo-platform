package com.tailtopia.gift.web;

import com.tailtopia.gift.dto.GiftCatalogResponse;
import com.tailtopia.gift.dto.GiftOrderStatusResponse;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.service.GiftCatalogService;
import com.tailtopia.gift.service.GiftOrderQueryService;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 送礼 App 接口（V1.4.0 gift Story 1.1 · AC9）。三条都只给 {@code role=USER}（{@code SecurityConfig} 精确 matcher）：
 * <ul>
 *   <li>{@code GET /api/v1/gifts/catalog?petId=|postId=} —— 礼物目录（二选一）；</li>
 *   <li>{@code POST /api/v1/pets/{petId}/gifts} —— 发起送礼；</li>
 *   <li>{@code GET /api/v1/me/gift-orders/{orderToken}} —— 单笔状态（QRIS 轮询，仅本人）。</li>
 * </ul>
 */
@RestController
public class GiftController {

    /** 发起限流：20 次 / 分钟 / 用户（与 {@code createIntent} 同档；PawCoin 渠道不经 createIntent，必须自己限）。 */
    private static final int SEND_LIMIT = 20;
    private static final Duration SEND_WINDOW = Duration.ofMinutes(1);

    private final GiftCatalogService catalogService;
    private final GiftSendService sendService;
    private final GiftOrderQueryService orderQuery;
    private final RedisRateLimiter rateLimiter;

    public GiftController(GiftCatalogService catalogService, GiftSendService sendService,
            GiftOrderQueryService orderQuery, RedisRateLimiter rateLimiter) {
        this.catalogService = catalogService;
        this.sendService = sendService;
        this.orderQuery = orderQuery;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/v1/gifts/catalog")
    public GiftCatalogResponse catalog(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long petId, @RequestParam(required = false) Long postId) {
        return catalogService.catalog(currentUserId(jwt), petId, postId);
    }

    @PostMapping("/api/v1/pets/{petId}/gifts")
    public GiftSendResponse send(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId,
            @Valid @RequestBody GiftSendRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:gift:send:" + userId, SEND_LIMIT, SEND_WINDOW);
        try {
            return sendService.send(userId, petId, req);
        } catch (DataIntegrityViolationException e) {
            // 并发同 requestId：另一请求已插入同幂等键的订单 → 新事务重读、按那笔返回；撞的不是幂等键则原样抛出。
            return sendService.replayExisting(userId, req.requestId()).orElseThrow(() -> e);
        }
    }

    @GetMapping("/api/v1/me/gift-orders/{orderToken}")
    public GiftOrderStatusResponse orderStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderToken) {
        return orderQuery.orderStatus(currentUserId(jwt), orderToken);
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
