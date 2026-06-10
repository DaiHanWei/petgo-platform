package com.petgo.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.service.ContentService;
import com.petgo.content.service.GrowthMomentView;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.dto.ArchiveStatsResponse;
import com.petgo.profile.service.ProfileService;
import com.petgo.profile.service.TimelineService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/** L0：名片 6 区块直出 + 里程碑零态 + 仅快乐时刻 + 多态失效统一 404 防枚举（AC1/AC4/AC5/AC7）。 */
class CardPageControllerTest {

    private ProfileService profileService;
    private ContentService contentService;
    private AccountQueryService accountQueryService;
    private TimelineService timelineService;
    private CardPageController controller;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        contentService = mock(ContentService.class);
        accountQueryService = mock(AccountQueryService.class);
        timelineService = mock(TimelineService.class);
        controller = new CardPageController(profileService, contentService, accountQueryService,
                timelineService, "https://dl", "https://ios", "https://android", "https://h5.petgo");
    }

    private PetProfile profile() {
        return PetProfile.create(7L, com.petgo.profile.domain.PetType.CAT, "Momo",
                "https://cdn/a.jpg", "Shiba", LocalDate.of(2022, 1, 1), "好奇宝宝", "TOK");
    }

    private void stubOwner(long happy, long consult, long milestoneCompleted) {
        when(accountQueryService.isActive(7L)).thenReturn(true);
        when(accountQueryService.findAuthorViews(any()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Aurel", null, false)));
        when(timelineService.getStats(7L))
                .thenReturn(new ArchiveStatsResponse(happy, consult, milestoneCompleted, 30));
    }

    @Test
    void validTokenRenders6BlockCardWithStrippedImagesAndStats() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        stubOwner(2, 1, 0);
        when(contentService.findRecentGrowthMomentsByEventDate(7L, 5))
                .thenReturn(List.of(new GrowthMomentView(
                        1L, Instant.now(), LocalDate.of(2024, 5, 1), List.of("https://cdn/m.jpg"), "hi")));

        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);
        String view = controller.card("TOK", model, resp);

        assertThat(view).isEqualTo("card");
        assertThat(model.getAttribute("name")).isEqualTo("Momo");
        assertThat(model.getAttribute("ownerNickname")).isEqualTo("Aurel");
        assertThat(model.getAttribute("ogTitle").toString()).contains("成长故事");
        assertThat(model.getAttribute("happyCount")).isEqualTo(2L);
        assertThat(model.getAttribute("consultCount")).isEqualTo(1L);
        assertThat(model.getAttribute("hasMilestones")).isEqualTo(false); // 里程碑零态
        assertThat(model.getAttribute("hasMoments")).isEqualTo(true);
        // E4：对外图带去 EXIF process 参数
        assertThat(model.getAttribute("avatarUrl").toString()).contains("x-oss-process=image/");
        @SuppressWarnings("unchecked")
        List<CardPageController.CardMoment> moments =
                (List<CardPageController.CardMoment>) model.getAttribute("moments");
        assertThat(moments).hasSize(1);
        assertThat(moments.get(0).getImageUrls().get(0)).contains("x-oss-process=image/");
    }

    @Test
    void zeroMomentsAndMilestonesDegradeGracefully() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        stubOwner(0, 0, 0);
        when(contentService.findRecentGrowthMomentsByEventDate(7L, 5)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);
        String view = controller.card("TOK", model, resp);

        assertThat(view).isEqualTo("card"); // 不抛错、不阻塞渲染（AC5）
        assertThat(model.getAttribute("hasMilestones")).isEqualTo(false);
        assertThat(model.getAttribute("hasMoments")).isEqualTo(false);
    }

    @Test
    void companionDaysIsDateDiffNonNegative() {
        Instant created = LocalDate.of(2026, 6, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant now = LocalDate.of(2026, 6, 9).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        assertThat(CardPageController.companionDays(created, now)).isEqualTo(8);
        assertThat(CardPageController.companionDays(now, created)).isEqualTo(0); // 不为负
        assertThat(CardPageController.companionDays(null, now)).isEqualTo(0);
        assertThat(ChronoUnit.DAYS.between(created, now)).isEqualTo(8); // 口径自证
    }

    @Test
    void unknownTokenRendersGoneWith404() {
        when(profileService.findByCardToken("NOPE")).thenReturn(Optional.empty());
        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);

        String view = controller.card("NOPE", model, resp);

        assertThat(view).isEqualTo("card_gone");
        verify(resp).setStatus(404);
    }

    @Test
    void deletedAccountRendersSameGonePageNoEnumerationLeak() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        when(accountQueryService.isActive(7L)).thenReturn(false); // 账号注销
        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);

        String view = controller.card("TOK", model, resp);

        // 与「不存在」完全一致：同视图 + 同 404，不泄漏 token 曾否存在
        assertThat(view).isEqualTo("card_gone");
        verify(resp).setStatus(404);
    }
}
