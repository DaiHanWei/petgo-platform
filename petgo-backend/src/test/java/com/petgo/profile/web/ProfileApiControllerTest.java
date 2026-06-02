package com.petgo.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.profile.dto.PetProfileCreateRequest;
import com.petgo.profile.dto.PetProfileResponse;
import com.petgo.profile.dto.TimelinePageResponse;
import com.petgo.profile.service.ProfileService;
import com.petgo.profile.service.TimelineService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：控制器 owner 取自 JWT（不信任客户端）+ 限流（AC1）+ 时间线委派（2.4）。 */
class ProfileApiControllerTest {

    private final ProfileService service = mock(ProfileService.class);
    private final TimelineService timelineService = mock(TimelineService.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final ProfileApiController controller =
            new ProfileApiController(service, timelineService, rateLimiter);

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void createUsesOwnerFromJwtAndRateLimits() {
        PetProfileResponse stub = new PetProfileResponse(
                5L, null, "Momo", null, null, null, "TOK", Instant.now());
        when(service.create(eq(77L), org.mockito.ArgumentMatchers.any())).thenReturn(stub);

        PetProfileResponse resp = controller.create(
                jwt("77"), new PetProfileCreateRequest(null, "Momo", null, null, null));

        assertThat(resp.name()).isEqualTo("Momo");
        verify(rateLimiter).check(eq("rl:profile:create:77"), anyInt(), org.mockito.ArgumentMatchers.any());
        verify(service).create(eq(77L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingJwt() {
        assertThatThrownBy(() -> controller.create(
                null, new PetProfileCreateRequest(null, "Momo", null, null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void myProfileDelegatesWithJwtUser() {
        PetProfileResponse stub = new PetProfileResponse(
                5L, null, "Momo", null, null, null, "TOK", Instant.now());
        when(service.getMyProfile(77L)).thenReturn(stub);
        assertThat(controller.myProfile(jwt("77")).cardToken()).isEqualTo("TOK");
    }

    @Test
    void timelineDelegatesWithJwtUserAndParams() {
        TimelinePageResponse stub = new TimelinePageResponse(List.of(), null, false);
        when(timelineService.getTimeline(77L, "CUR", 20)).thenReturn(stub);
        TimelinePageResponse resp = controller.timeline(jwt("77"), "CUR", 20);
        assertThat(resp.hasMore()).isFalse();
    }
}
