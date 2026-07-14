package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.dto.PetProfileCreateRequest;
import com.tailtopia.profile.dto.PetProfileResponse;
import com.tailtopia.profile.dto.PetProfileUpdateRequest;
import com.tailtopia.profile.dto.TimelinePageResponse;
import com.tailtopia.profile.service.CardRerenderService;
import com.tailtopia.profile.service.IdCardHdService;
import com.tailtopia.profile.service.IdCardService;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.profile.service.TimelineService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：控制器 owner 取自 JWT（不信任客户端）+ 限流（AC1）+ 时间线委派（2.4）+ 编辑联动重渲染（2.8）。 */
class ProfileApiControllerTest {

    private final ProfileService service = mock(ProfileService.class);
    private final TimelineService timelineService = mock(TimelineService.class);
    private final CardRerenderService cardRerenderService = mock(CardRerenderService.class);
    private final IdCardService idCardService = mock(IdCardService.class);
    private final IdCardHdService idCardHdService = mock(IdCardHdService.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final ProfileApiController controller = new ProfileApiController(
            service, timelineService, cardRerenderService, idCardService, idCardHdService, rateLimiter);

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void createUsesOwnerFromJwtAndRateLimits() {
        PetProfileResponse stub = new PetProfileResponse(
                5L, null, "CAT", "Momo", null, null, null, "TOK", Instant.now());
        when(service.create(eq(77L), org.mockito.ArgumentMatchers.any())).thenReturn(stub);

        PetProfileResponse resp = controller.create(
                jwt("77"), new PetProfileCreateRequest(null, "CAT", "Momo", null, java.time.LocalDate.of(2022,1,1), null));

        assertThat(resp.name()).isEqualTo("Momo");
        verify(rateLimiter).check(eq("rl:profile:create:77"), anyInt(), org.mockito.ArgumentMatchers.any());
        verify(service).create(eq(77L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingJwt() {
        assertThatThrownBy(() -> controller.create(
                null, new PetProfileCreateRequest(null, "CAT", "Momo", null, java.time.LocalDate.of(2022,1,1), null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void myProfileDelegatesWithJwtUser() {
        PetProfileResponse stub = new PetProfileResponse(
                5L, null, "CAT", "Momo", null, null, null, "TOK", Instant.now());
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

    @Test
    void updateUsesJwtOwnerAndTriggersRerender() {
        PetProfileResponse updated = new PetProfileResponse(
                5L, null, "CAT", "Momo2", null, null, null, "TOK", Instant.now());
        when(service.update(eq(77L), ArgumentMatchers.any())).thenReturn(updated);

        PetProfileResponse resp = controller.update(
                jwt("77"), new PetProfileUpdateRequest(null, "Momo2", null, null, null));

        assertThat(resp.name()).isEqualTo("Momo2");
        verify(service).update(eq(77L), ArgumentMatchers.any());
        // 编辑联动：异步触发 OG 重渲染
        verify(cardRerenderService).scheduleRerender(5L);
    }
}
