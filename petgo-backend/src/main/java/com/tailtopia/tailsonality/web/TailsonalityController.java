package com.tailtopia.tailsonality.web;

import com.tailtopia.purchase.dto.KeepsakePayRequest;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.tailsonality.dto.TailsonalityResultListResponse;
import com.tailtopia.tailsonality.dto.TailsonalityResultResponse;
import com.tailtopia.tailsonality.dto.BadgeEquipRequest;
import com.tailtopia.tailsonality.service.TailsonalityBadgeService;
import com.tailtopia.tailsonality.service.TailsonalityResultService;
import com.tailtopia.tailsonality.service.TailsonalityUnlockService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tailsonality 结果接口（V1.3.2 Story 2.1）。仅 {@code role=USER}（{@code SecurityConfig} 显式 matcher）。
 *
 * <p>请求体只收 18 个题号键；题套由服务端按宠物物种选，body 里带题套会让键集合校验失败（422）。
 */
@RestController
@RequestMapping("/api/v1/pet-profiles/me/tailsonality")
public class TailsonalityController {

    /** 提交限流：10 次 / 分钟 / 用户。 */
    private static final int SUBMIT_LIMIT = 10;
    private static final Duration SUBMIT_WINDOW = Duration.ofMinutes(1);
    /** 解锁发起限流（Story 3.2 · AC1.5）：10 次 / 分钟 / 用户。 */
    private static final int UNLOCK_LIMIT = 10;
    private static final Duration UNLOCK_WINDOW = Duration.ofMinutes(1);

    /** 佩戴切换 / 卸下限流（Story 3.3 · AC2.3）：20 次 / 分钟 / 用户（两动作共用一个桶）。 */
    private static final int BADGE_LIMIT = 20;
    private static final Duration BADGE_WINDOW = Duration.ofMinutes(1);

    private final TailsonalityResultService service;
    private final TailsonalityUnlockService unlockService;
    private final TailsonalityBadgeService badgeService;
    private final RedisRateLimiter rateLimiter;

    public TailsonalityController(TailsonalityResultService service, TailsonalityUnlockService unlockService,
            TailsonalityBadgeService badgeService, RedisRateLimiter rateLimiter) {
        this.service = service;
        this.unlockService = unlockService;
        this.badgeService = badgeService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/results")
    @ResponseStatus(HttpStatus.CREATED)
    public TailsonalityResultResponse submit(@AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, Object> body) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:tailsonality:submit:" + userId, SUBMIT_LIMIT, SUBMIT_WINDOW);
        return service.submit(userId, body);
    }

    @GetMapping("/results")
    public TailsonalityResultListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(currentUserId(jwt));
    }

    @GetMapping("/results/{token}")
    public TailsonalityResultResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return service.get(currentUserId(jwt), token);
    }

    /** 一次性解锁（Story 3.2）：PawCoin 当场成交 / QRIS 返回二维码；响应形状见 {@link KeepsakePurchaseResponse}。 */
    @PostMapping("/results/{token}/unlock")
    public KeepsakePurchaseResponse unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @Valid @RequestBody KeepsakePayRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:tailsonality:unlock:" + userId, UNLOCK_LIMIT, UNLOCK_WINDOW);
        return unlockService.unlock(userId, token, req.channel());
    }

    /** 配型单独解锁（2026-10-09）：与 {@link #unlock} 同形、同限流桶（同一个人的付费点击共用一份额度）。 */
    @PostMapping("/results/{token}/match-unlock")
    public KeepsakePurchaseResponse unlockMatch(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @Valid @RequestBody KeepsakePayRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:tailsonality:unlock:" + userId, UNLOCK_LIMIT, UNLOCK_WINDOW);
        return unlockService.unlockMatch(userId, token, req.channel());
    }

    /** 佩戴某个已解锁结果（Story 3.3 · AC2）：未解锁 422 {@code tailsonality-badge-locked}；成功 204。 */
    @PutMapping("/badge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void equipBadge(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BadgeEquipRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:tailsonality:badge:" + userId, BADGE_LIMIT, BADGE_WINDOW);
        badgeService.equip(userId, req.resultToken());
    }

    /** 卸下（D-16）：幂等，无佩戴也 204。 */
    @DeleteMapping("/badge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unequipBadge(@AuthenticationPrincipal Jwt jwt) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:tailsonality:badge:" + userId, BADGE_LIMIT, BADGE_WINDOW);
        badgeService.unequip(userId);
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
