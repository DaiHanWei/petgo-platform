package com.petgo.profile.web;

import com.petgo.profile.dto.PetProfileCreateRequest;
import com.petgo.profile.dto.PetProfileResponse;
import com.petgo.profile.service.ProfileService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物档案端点（Story 2.2）。资源化命名 {@code /api/v1/pet-profiles}。
 *
 * <ul>
 *   <li>{@code POST /pet-profiles}：创建（201）；单账号单宠物，重复 409。owner 取自 JWT。</li>
 *   <li>{@code GET /pet-profiles/me}：当前用户档案（无则 404）——支撑「已有档案直达」。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/pet-profiles")
public class ProfileApiController {

    private static final int CREATE_LIMIT = 10;
    private static final Duration CREATE_WINDOW = Duration.ofMinutes(1);

    private final ProfileService profileService;
    private final RedisRateLimiter rateLimiter;

    public ProfileApiController(ProfileService profileService, RedisRateLimiter rateLimiter) {
        this.profileService = profileService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PetProfileResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PetProfileCreateRequest req) {
        long ownerId = currentUserId(jwt);
        rateLimiter.check("rl:profile:create:" + ownerId, CREATE_LIMIT, CREATE_WINDOW);
        return profileService.create(ownerId, req);
    }

    @GetMapping("/me")
    public PetProfileResponse myProfile(@AuthenticationPrincipal Jwt jwt) {
        return profileService.getMyProfile(currentUserId(jwt));
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
