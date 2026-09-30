package com.tailtopia.tailsonality.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.tailsonality.dto.TailsonalityResultListResponse;
import com.tailtopia.tailsonality.dto.TailsonalityResultResponse;
import com.tailtopia.tailsonality.service.TailsonalityResultService;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final TailsonalityResultService service;
    private final RedisRateLimiter rateLimiter;

    public TailsonalityController(TailsonalityResultService service, RedisRateLimiter rateLimiter) {
        this.service = service;
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
