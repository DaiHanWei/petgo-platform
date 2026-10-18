package com.tailtopia.passport.web;

import com.tailtopia.passport.dto.BoardingPassDetailResponse;
import com.tailtopia.passport.dto.BoardingPassListResponse;
import com.tailtopia.passport.service.BoardingPassService;
import com.tailtopia.purchase.dto.KeepsakePayRequest;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 登机牌（V1.3.2 Story 3.5）。仅 {@code role=USER}（SecurityConfig 精确 matcher）。 */
@RestController
@RequestMapping("/api/v1/pet-profiles/me/boarding-passes")
public class BoardingPassController {

    /** 单张解锁限流（AC5.5）：10 次 / 分钟 / 用户。 */
    private static final int UNLOCK_LIMIT = 10;
    private static final Duration UNLOCK_WINDOW = Duration.ofMinutes(1);

    private final BoardingPassService service;
    private final RedisRateLimiter rateLimiter;
    /** V1.4.0 multi-pet：按宠物端点的归属校验（不属于本人 → 404）。 */
    private final com.tailtopia.profile.service.PetResolver petResolver;

    public BoardingPassController(BoardingPassService service, RedisRateLimiter rateLimiter,
            com.tailtopia.profile.service.PetResolver petResolver) {
        this.petResolver = petResolver;
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public BoardingPassListResponse list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "petId", required = false) Long petId) {
        long userId = currentUserId(jwt);
        // TODO(v1.4.0-multi-pet-1.2): 把 pet.getId() 传入服务层
        petResolver.resolveOptional(userId, petId);
        return service.list(userId);
    }

    @GetMapping("/{placeToken}")
    public BoardingPassDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String placeToken,
            @RequestParam(name = "petId", required = false) Long petId) {
        long userId = currentUserId(jwt);
        // TODO(v1.4.0-multi-pet-1.2): 把 pet.getId() 传入服务层
        petResolver.resolveOptional(userId, petId);
        return service.detail(userId, placeToken);
    }

    @PostMapping("/{placeToken}/unlock")
    public KeepsakePurchaseResponse unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable String placeToken,
            @Valid @RequestBody KeepsakePayRequest req,
            @RequestParam(name = "petId", required = false) Long petId) {
        long userId = currentUserId(jwt);
        // TODO(v1.4.0-multi-pet-1.2): 把 pet.getId() 传入服务层
        petResolver.resolveOptional(userId, petId);
        rateLimiter.check("rl:boarding:unlock:" + userId, UNLOCK_LIMIT, UNLOCK_WINDOW);
        return service.unlock(userId, placeToken, req.channel());
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
