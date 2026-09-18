package com.tailtopia.mention.web;

import com.tailtopia.mention.dto.MentionCandidatesResponse;
import com.tailtopia.mention.service.MentionCandidateQueryService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @ 候选集端点（V1.3.0 batch-b1 Story 3.1 · FR-119 · AD-10）。
 * {@code GET /api/v1/me/mention-candidates}
 *
 * <h2>为什么挂在 {@code /me} 下</h2>
 * 候选集是**当前用户自己的**东西（决策 C1：当前用户统一 {@code /api/v1/me}，不用 {@code /users/me}）。
 * 挂成 {@code /users/{id}/mention-candidates} 会立刻带出一个问题：
 * 别人的候选集凭什么给你看 —— 而它根本就不该有那个语义。
 *
 * <h2>🔴 需要登录，且**没有任何查询参数**</h2>
 * 不在 {@code SecurityConfig} 里放行，落进默认的 authenticated 规则。
 * <p>⚠️ <b>不接受关键词</b>（AC5）：没有全局用户搜索，昵称过滤在客户端于这 30 人之内做。
 * 加一个 {@code ?q=} 形参就等于把它变成全局用户搜索接口，而搜索留在 1.6.0、**未前移**。
 * <p>⚠️ 也<b>不分页</b>：一次性给完的 30 人小表，分页只会多一处要防的输入。
 */
@RestController
public class MentionCandidateController {

    private final MentionCandidateQueryService candidates;

    public MentionCandidateController(MentionCandidateQueryService candidates) {
        this.candidates = candidates;
    }

    @GetMapping("/api/v1/me/mention-candidates")
    public MentionCandidatesResponse candidates(@AuthenticationPrincipal Jwt jwt) {
        return new MentionCandidatesResponse(candidates.candidatesFor(currentUserId(jwt)));
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
