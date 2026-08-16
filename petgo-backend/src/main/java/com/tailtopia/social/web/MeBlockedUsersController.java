package com.tailtopia.social.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.dto.BlockedUserItem;
import com.tailtopia.social.service.BlockedUsersQueryService;
import com.tailtopia.social.service.UserHideRelationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * <p><b>Story 1.5 追加黑名单列表（GET）</b>：三个动词共用同一资源路径。
 */
@RestController
@RequestMapping("/api/v1/me/blocked-users")
public class MeBlockedUsersController {

    private final UserHideRelationService hideRelations;
    private final BlockedUsersQueryService blockedUsers;

    public MeBlockedUsersController(UserHideRelationService hideRelations,
            BlockedUsersQueryService blockedUsers) {
        this.hideRelations = hideRelations;
        this.blockedUsers = blockedUsers;
    }

    /**
     * 我的黑名单（Story 1.5）。<b>裸 {@code List<T>} 全量返回</b>，无信封、无游标、无 total ——
     * 与 {@code MeRefundController.myRefunds} 同款（{@code /me/*} 的小列表惯例）。
     * 前端设置页要显示的「拉黑数量」直接取列表长度。
     */
    @GetMapping
    public List<BlockedUserItem> myBlockedUsers(@AuthenticationPrincipal Jwt jwt) {
        return blockedUsers.listBlocked(currentUserId(jwt));
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
