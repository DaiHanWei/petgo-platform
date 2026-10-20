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
    /** Story 18.2：本类不验分享奖励（有 IdCardShareRewardIntegrationTest），给个哑桩。 */
    private final com.tailtopia.share.service.IdCardShareRewardService idCardShareRewards =
            mock(com.tailtopia.share.service.IdCardShareRewardService.class);
    /** V1.3.0 Story 5.3：同上，本类不验年龄卡分享奖励（见 AgeCardShareRewardTest）。 */
    private final com.tailtopia.share.service.AgeCardShareRewardService ageCardShareRewards =
            mock(com.tailtopia.share.service.AgeCardShareRewardService.class);
    /** V1.3.2 Story 4.5：两个新渠道（发放规则见 PetCardShareRewardIntegrationTest），这里只验委派与限流。 */
    private final com.tailtopia.share.service.TailsonalityShareRewardService tailsonalityShareRewards =
            mock(com.tailtopia.share.service.TailsonalityShareRewardService.class);
    private final com.tailtopia.share.service.PassportShareRewardService passportShareRewards =
            mock(com.tailtopia.share.service.PassportShareRewardService.class);
    /** V1.4.0 multi-pet：控制器取宠物只经 PetResolver；不带 petId 的入口校验返回 empty 即放行。 */
    private final com.tailtopia.profile.service.PetResolver petResolver =
            mock(com.tailtopia.profile.service.PetResolver.class);
    private final ProfileApiController controller = new ProfileApiController(
            service, timelineService, cardRerenderService, idCardService, idCardHdService,
            rateLimiter, idCardShareRewards, ageCardShareRewards, tailsonalityShareRewards, passportShareRewards,
            petResolver, mock(com.tailtopia.profile.service.PetListService.class));

    @Test
    void tailsonalityShareRewardDelegatesWithJwtUserAndRateLimits() {
        when(tailsonalityShareRewards.rewardAfterShare(eq(77L), ArgumentMatchers.isNull(),
                eq(com.tailtopia.share.service.TailsonalityShareRewardService.CardType.MATCH),
                ArgumentMatchers.any())).thenReturn(15L);
        var resp = controller.rewardTailsonalityShare(jwt("77"), null,
                new com.tailtopia.share.dto.TailsonalityShareRewardRequest("MATCH"));
        assertThat(resp.coins()).isEqualTo(15L);
        verify(rateLimiter).check(eq("rl:profile:tsshare:77"), anyInt(), ArgumentMatchers.any());
    }

    @Test
    void passportShareRewardDelegatesWithJwtUserAndRateLimits() {
        when(passportShareRewards.rewardAfterShare(eq(77L), ArgumentMatchers.isNull(),
                eq(com.tailtopia.share.service.PassportShareRewardService.CardType.BOARDING),
                ArgumentMatchers.any())).thenReturn(0L);
        var resp = controller.rewardPassportShare(jwt("77"), null,
                new com.tailtopia.share.dto.PassportShareRewardRequest("BOARDING"));
        assertThat(resp.coins()).isZero();
        verify(rateLimiter).check(eq("rl:profile:ppshare:77"), anyInt(), ArgumentMatchers.any());
    }

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void createUsesOwnerFromJwtAndRateLimits() {
        PetProfileResponse stub = new PetProfileResponse(
                5L, null, "CAT", "Momo", null, null, null, null, "TOK", false, null, null, Instant.now(), null);
        when(service.create(eq(77L), org.mockito.ArgumentMatchers.any(), eq(true))).thenReturn(stub);

        PetProfileResponse resp = controller.create(
                jwt("77"), "1.4.0", new PetProfileCreateRequest(null, "CAT", "Momo", null, java.time.LocalDate.of(2022,1,1), null));

        assertThat(resp.name()).isEqualTo("Momo");
        verify(rateLimiter).check(eq("rl:profile:create:77"), anyInt(), org.mockito.ArgumentMatchers.any());
        verify(service).create(eq(77L), org.mockito.ArgumentMatchers.any(), eq(true));
    }

    /** 旧版兼容：缺 X-App-Version（≤1.3.3）/ 1.3.4 → 以旧版客户端调用 service（multiPetClient = false）。 */
    @Test
    void createPassesLegacyFlagForOldOrMissingVersion() {
        PetProfileResponse stub = new PetProfileResponse(
                5L, null, "CAT", "Momo", null, null, null, null, "TOK", false, null, null, Instant.now(), null);
        when(service.create(eq(77L), org.mockito.ArgumentMatchers.any(), eq(false))).thenReturn(stub);
        var req = new PetProfileCreateRequest(null, "CAT", "Momo", null, java.time.LocalDate.of(2022,1,1), null);

        controller.create(jwt("77"), null, req);
        controller.create(jwt("77"), "1.3.4", req);

        verify(service, org.mockito.Mockito.times(2)).create(eq(77L), org.mockito.ArgumentMatchers.any(), eq(false));
    }

    @Test
    void rejectsMissingJwt() {
        assertThatThrownBy(() -> controller.create(
                null, null, new PetProfileCreateRequest(null, "CAT", "Momo", null, java.time.LocalDate.of(2022,1,1), null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void myProfileDelegatesWithJwtUser() {
        PetProfileResponse stub = new PetProfileResponse(
                5L, null, "CAT", "Momo", null, null, null, null, "TOK", false, null, null, Instant.now(), null);
        when(service.getMyProfile(77L, null)).thenReturn(stub);
        assertThat(controller.myProfile(jwt("77"), null).cardToken()).isEqualTo("TOK");
    }

    @Test
    void timelineDelegatesWithJwtUserAndParams() {
        TimelinePageResponse stub = new TimelinePageResponse(List.of(), null, false);
        when(timelineService.getTimeline(77L, null, "CUR", 20,
                com.tailtopia.profile.service.TimelineCapabilities.none(), false)).thenReturn(stub);
        TimelinePageResponse resp = controller.timeline(jwt("77"), "CUR", 20, null, false, null);
        assertThat(resp.hasMore()).isFalse();
    }

    @Test
    void updateUsesJwtOwnerAndTriggersRerender() {
        // 参数序：id, avatarUrl, petType, name, breed, birthday, sex, intro, cardToken, isSystemDefaultName, createdAt
        PetProfileResponse updated = new PetProfileResponse(
                5L, null, "CAT", "Momo2", null, null, null, null, "TOK", false, null, null, Instant.now(), null);
        when(service.update(eq(77L), ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(updated);

        // 参数序：avatarUrl, name, breed, birthday, sex, intro, weightKg, neuterStatus
        PetProfileResponse resp = controller.update(
                jwt("77"), null, new PetProfileUpdateRequest(null, "Momo2", null, null, null, null, null, null));

        assertThat(resp.name()).isEqualTo("Momo2");
        verify(service).update(eq(77L), ArgumentMatchers.isNull(), ArgumentMatchers.any());
        // 编辑联动：异步触发 OG 重渲染
        verify(cardRerenderService).scheduleRerender(5L);
    }
}
