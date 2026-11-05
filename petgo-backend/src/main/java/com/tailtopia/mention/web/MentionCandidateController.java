package com.tailtopia.mention.web;

import com.tailtopia.mention.dto.MentionCandidatesResponse;
import com.tailtopia.mention.service.MentionCandidateQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @ 候选集端点（V1.3.0 batch-b1 Story 3.1 · FR-119 · AD-10；V1.4.2 social-topic Story 5.1 · FR-157 · D-15）。
 * {@code GET /api/v1/me/mention-candidates[?q=]}
 *
 * <h2>为什么挂在 {@code /me} 下</h2>
 * 候选集是**当前用户自己的**东西（决策 C1：当前用户统一 {@code /api/v1/me}，不用 {@code /users/me}）。
 * 挂成 {@code /users/{id}/mention-candidates} 会立刻带出一个问题：
 * 别人的候选集凭什么给你看 —— 而它根本就不该有那个语义。
 *
 * <h2>🔴 需要登录；唯一的查询参数是可选的 {@code q}</h2>
 * 不在 {@code SecurityConfig} 里放行，落进默认的 authenticated 规则。
 * <ul>
 *   <li>不带 {@code q}（或去空白后为空）：快捷列表 —— 我关注的人 + 互动候选补齐，最多 30 人，不含粉丝；</li>
 *   <li>带 {@code q}：<b>只在「我关注 ∪ 关注我 ∪ 互动候选」三类范围内</b>按昵称包含匹配，最多 20 条；
 *       去空白后超过 20 字 → 422。</li>
 * </ul>
 * <p>⚠️ <b>这不是全局用户搜索</b>：范围外的人搜不到（昵称完全一致也不行）。全局搜索归 1.5.4，
 * 别再给它加「任意用户」之类的参数。
 * <p>⚠️ 带 {@code q} 限流 {@value #SEARCH_LIMIT_PER_MINUTE} 次 / 分钟 / 人（超限 429）：App 300ms 防抖后
 * 正常远低于此，限流防的是脚本逐字枚举粉丝昵称。不带 {@code q} 不限流（与改造前一致）。
 * <p>⚠️ <b>不分页</b>：上限即边界。
 */
@RestController
public class MentionCandidateController {

    /** 带 {@code q} 的每人每分钟上限（拍板点 ②）。 */
    static final int SEARCH_LIMIT_PER_MINUTE = 60;

    private final MentionCandidateQueryService candidates;
    private final RedisRateLimiter rateLimiter;

    public MentionCandidateController(MentionCandidateQueryService candidates,
            RedisRateLimiter rateLimiter) {
        this.candidates = candidates;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/v1/me/mention-candidates")
    public MentionCandidatesResponse candidates(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "q", required = false) String q) {
        long userId = currentUserId(jwt);
        if (q == null || q.isBlank()) {
            return new MentionCandidatesResponse(candidates.candidatesFor(userId));
        }
        // 🛡 先限流再查（超限不碰库）。不记 q：它是人名片段（PII 边缘）。
        rateLimiter.check("rl:mention-search:" + userId, SEARCH_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
        return new MentionCandidatesResponse(candidates.search(userId, q));
    }

    /**
     * 当前登录<b>用户</b> id。
     *
     * <p>⚠️ 只认 {@code role=USER}：兽医 token 的 {@code sub=vetId} 与 {@code users.id}
     * 是两个会碰撞的命名空间 —— 拿它当 ownerId 查出来的是某个无关用户的候选集。
     * 本端点需要登录，所以这里直接 401 而不是按游客降级。
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
