package com.tailtopia.ops.popup.web;

import com.tailtopia.ops.popup.dto.OpsPopupCandidatesResponse;
import com.tailtopia.ops.popup.service.OpsPopupCandidateService;
import com.tailtopia.ops.popup.service.OpsPopupImpressionService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营弹窗 App 接口（V1.3.4 Story 5.1 · AD-14）。
 *
 * <ul>
 *   <li>{@code GET /candidates}：游客可读（SecurityConfig 精确 permitAll），带 USER token 时识别用户；</li>
 *   <li>{@code POST /{token}/impressions}：仅 {@code role=USER}（SecurityConfig 精确 hasRole），无请求体，204。</li>
 * </ul>
 *
 * <p>🔴 本类<b>只读 + 曝光上报</b>：弹窗的新建 / 编辑 / 停用属 admin 主题（AB-30，D-2），不在这里加写端点。
 * 🔴 路径与返回体只出现 public_token，不出现自增 id（D-24）。
 */
@RestController
@RequestMapping("/api/v1/ops-popups")
public class OpsPopupController {

    /** 曝光上报限流：30/分钟。一次冷启动最多弹一个，正常远低于此；挡的是脚本刷曝光。 */
    private static final int IMPRESSION_LIMIT = 30;
    private static final Duration IMPRESSION_WINDOW = Duration.ofMinutes(1);

    private final OpsPopupCandidateService candidates;
    private final OpsPopupImpressionService impressions;
    private final RedisRateLimiter rateLimiter;

    public OpsPopupController(OpsPopupCandidateService candidates, OpsPopupImpressionService impressions,
            RedisRateLimiter rateLimiter) {
        this.candidates = candidates;
        this.impressions = impressions;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/candidates")
    public OpsPopupCandidatesResponse candidates(@AuthenticationPrincipal Jwt jwt) {
        return new OpsPopupCandidatesResponse(candidates.candidates(viewerId(jwt)));
    }

    @PostMapping("/{token}/impressions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void impression(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:ops-popup:impression:" + userId, IMPRESSION_LIMIT, IMPRESSION_WINDOW);
        impressions.record(token, userId);
    }

    /**
     * 登录<b>用户</b> id（游客 / 无效 JWT / <b>非 USER 角色</b> → null）。
     *
     * <p>⚠️ 候选端点 permitAll，兽医 token 也进得来。必须只认 {@code role=USER}——否则兽医的
     * {@code sub=vetId} 会被当成碰撞的 {@code users.id}，拿无关用户的注册时间 / 曝光记录做判断
     * （同 {@code MiniProfileController}）。非用户一律按游客。
     */
    private static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 曝光端点已由 SecurityConfig 限定 hasRole USER；此处只做防御性解析。 */
    private static long currentUserId(Jwt jwt) {
        Long id = viewerId(jwt);
        if (id == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        return id;
    }
}
