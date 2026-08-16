package com.tailtopia.social.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.service.UserHideRelationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 拉黑 / 解除拉黑端点（Story 1.1，FR-94）。{@code /api/v1/me/blocked-users}。
 *
 * <p>鉴权由 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 覆盖（{@code /api/v1/me/**}），
 * 无需改安全配置。
 *
 * <p><b>黑名单列表（GET）归 Story 1.5</b>，本 story 不实现。
 */
@RestController
@RequestMapping("/api/v1/me/blocked-users")
public class MeBlockedUsersController {

    private final UserHideRelationService hideRelations;

    public MeBlockedUsersController(UserHideRelationService hideRelations) {
        this.hideRelations = hideRelations;
    }

    /** 拉黑某人。幂等：重复拉黑不报错、不新增记录、不刷新拉黑时间。 */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BlockRequest req) {
        hideRelations.block(currentUserId(jwt), req.targetUserId());
    }

    /** 解除拉黑。只删 BLOCK 行；重复解除静默成功。 */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId) {
        hideRelations.unblock(currentUserId(jwt), userId);
    }

    /** 拉黑请求体。 */
    public record BlockRequest(@NotNull(message = "请指定要拉黑的用户") Long targetUserId) {
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
