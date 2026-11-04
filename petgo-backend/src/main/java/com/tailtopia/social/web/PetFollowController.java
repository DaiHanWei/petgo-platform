package com.tailtopia.social.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.dto.FollowedPetPage;
import com.tailtopia.social.dto.PetFollowResultResponse;
import com.tailtopia.social.service.PetFollowService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 追更宠物端点（V1.4.2 social-topic · Story 1.1 · D-8）。
 *
 * <ul>
 *   <li>{@code POST / DELETE /api/v1/me/pet-follows/{petId}}：追更 / 取消追更，两个动词都幂等。
 *       {@code SecurityConfig} 精确 {@code hasRole("USER")}（兽医 token 403、游客 401）。
 *       {@code petId} 与站内访客路由 {@code /pets/{petId}} 同一标识，未新增可枚举暴露面。</li>
 *   <li>{@code GET /api/v1/users/{token}/pet-follows?cursor=}：某用户追更的宠物，游客可看
 *       （{@code SecurityConfig} 精确 permitAll，与 {@code /following} 同口径）；viewer 只认 {@code role=USER}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class PetFollowController {

    private final PetFollowService petFollows;

    public PetFollowController(PetFollowService petFollows) {
        this.petFollows = petFollows;
    }

    @PostMapping("/me/pet-follows/{petId}")
    public PetFollowResultResponse follow(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId) {
        return petFollows.follow(currentUserId(jwt), petId);
    }

    @DeleteMapping("/me/pet-follows/{petId}")
    public PetFollowResultResponse unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId) {
        return petFollows.unfollow(currentUserId(jwt), petId);
    }

    /** {@code token} 用户追更的宠物（AC8）。 */
    @GetMapping("/users/{token}/pet-follows")
    public FollowedPetPage followingPets(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @RequestParam(required = false) String cursor) {
        return petFollows.followingOf(token, viewerId(jwt), cursor);
    }

    /**
     * 列表的查看者：登录<b>用户</b> id；游客 / 无效 JWT / 非 USER 角色 → null（关系字段全 false）。
     * ⚠️ 兽医 {@code sub=vetId} 与 users.id 是会碰撞的两个命名空间（同 {@code FollowController}）。
     */
    static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static long currentUserId(Jwt jwt) {
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
