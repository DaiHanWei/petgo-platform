package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.profile.service.TimelineService;
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
    private com.tailtopia.profile.service.MilestoneService milestoneService;
    private CardPageController controller;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        contentService = mock(ContentService.class);
        accountQueryService = mock(AccountQueryService.class);
        timelineService = mock(TimelineService.class);
        milestoneService = mock(com.tailtopia.profile.service.MilestoneService.class);
        // V1.1.6 Story 2.1：控制器不再直连这些 service，改经访客投影层。
        // ⚠️ 这里用**真的**投影层套在同一批 mock 上，而不是把投影层也 mock 掉 ——
        // 否则下面那条「零里程碑不许碰 MilestoneService」的用例就会变成自说自话
        // （它要守的正是投影层里那个判断）。
        var visitors = new com.tailtopia.profile.visitor.VisitorProjectionService(
                profileService, accountQueryService, contentService, milestoneService,
                timelineService);
        controller = new CardPageController(visitors,
                mock(com.tailtopia.profile.service.OgImageService.class),
                new com.tailtopia.profile.service.CardPageAnalytics(
                        mock(com.tailtopia.shared.analytics.AnalyticsClient.class)),
                "https://dl", "https://ios", "https://android", "https://h5.petgo");
    }

    private PetProfile profile() {
        PetProfile p = PetProfile.create(7L, com.tailtopia.profile.domain.PetType.CAT, "Momo",
                "https://cdn/a.jpg", "Shiba", LocalDate.of(2022, 1, 1), "好奇宝宝", "TOK");
        setField(p, "id", 10L);
        return p;
    }

    /** 埋点需要 HttpServletRequest 取 UA / referrer / cookie；L0 用 mock 即可。 */
    private static jakarta.servlet.http.HttpServletRequest req() {
        return new org.springframework.mock.web.MockHttpServletRequest();
    }

    private static void setField(Object o, String name, Object value) {
        try {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubOwner(long happy, long consult, long milestoneCompleted) {
        when(accountQueryService.isActive(7L)).thenReturn(true);
        when(accountQueryService.findAuthorViews(any()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Aurel", null, false)));
        when(timelineService.getStats(7L))
                .thenReturn(new ArchiveStatsResponse(happy, consult, milestoneCompleted, 30, 0));
    }

    @Test
    void validTokenRenders6BlockCardWithStrippedImagesAndStats() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        stubOwner(2, 1, 0);
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt()))
                .thenReturn(List.of(new GrowthMomentView(
                        1L, Instant.now(), LocalDate.of(2024, 5, 1), List.of("https://cdn/m.jpg"), "hi",
                com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                com.tailtopia.content.domain.PostStatus.PUBLISHED)));

        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);
        String view = controller.card("TOK", model, req(), resp);

        assertThat(view).isEqualTo("card");
        assertThat(model.getAttribute("name")).isEqualTo("Momo");
        assertThat(model.getAttribute("ownerNickname")).isEqualTo("Aurel");
        // OG 标题已印尼语化（修 20260702-208：原硬编码中文「成长故事」→「Kisah tumbuh kembang」）。
        assertThat(model.getAttribute("ogTitle").toString()).contains("Kisah tumbuh kembang");
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
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);
        String view = controller.card("TOK", model, req(), resp);

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

        String view = controller.card("NOPE", model, req(), resp);

        assertThat(view).isEqualTo("card_gone");
        verify(resp).setStatus(404);
    }

    @Test
    void deletedAccountRendersSameGonePageNoEnumerationLeak() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        when(accountQueryService.isActive(7L)).thenReturn(false); // 账号注销
        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);

        String view = controller.card("TOK", model, req(), resp);

        // 与「不存在」完全一致：同视图 + 同 404，不泄漏 token 曾否存在
        assertThat(view).isEqualTo("card_gone");
        verify(resp).setStatus(404);
    }

    // ===== V1.1.6 Story 1.2：里程碑收藏区 + 元信息行 =====

    /** 造一个「已完成」的里程碑项。 */
    private static com.tailtopia.profile.dto.MilestoneItemResponse done(String code, Instant at) {
        return new com.tailtopia.profile.dto.MilestoneItemResponse(
                code, "中文标题", "S", "SYSTEM_AUTO", true, at);
    }

    private void stubMilestones(com.tailtopia.profile.dto.MilestoneItemResponse... items) {
        // 参数序：level, completedCount, totalCount, items
        var group = new com.tailtopia.profile.dto.MilestoneGroupResponse(
                "S", items.length, items.length, List.of(items));
        // 参数序：petName, petAvatarUrl, completedCount, totalCount, groups
        when(milestoneService.getMilestones(7L)).thenReturn(
                new com.tailtopia.profile.dto.MilestoneListResponse(
                        "Momo", null, items.length, items.length, List.of(group)));
    }

    /**
     * 🔴 <b>本组最要紧的一条</b>：零里程碑时<b>绝不调用</b> {@code getMilestones()}。
     *
     * <p>那个方法标了 {@code @Transactional} 且 roster 缺失时会 lazy 物化（写 {@code pet_milestones}）。
     * H5 是对匿名公众开放的页面 —— 无条件调用等于让每个陌生人的一次 GET 都可能触发写库。
     */
    @Test
    void zeroMilestonesNeverTouchesMilestoneService() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        stubOwner(0, 0, 0);
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt())).thenReturn(List.of());

        controller.card("TOK", new ConcurrentModel(), req(), mock(HttpServletResponse.class));

        org.mockito.Mockito.verify(milestoneService, org.mockito.Mockito.never()).getMilestones(7L);
    }

    /** 总数按物种取常量目录（猫 31），<b>不是</b>视觉稿里那个示意的 30。 */
    @Test
    void milestoneTotalComesFromCatalogNotTheMockupNumber() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        stubOwner(0, 0, 0);
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.card("TOK", model, req(), mock(HttpServletResponse.class));

        int catTotal = com.tailtopia.profile.domain.MilestoneCatalog
                .forType(com.tailtopia.profile.domain.PetType.CAT).size();
        assertThat(catTotal).isEqualTo(31);
        assertThat(model.getAttribute("milestoneTotal")).isEqualTo(catTotal);
        assertThat(model.getAttribute("milestoneMore")).isEqualTo(31L); // 一个没完成 → +31
    }

    /** 具名徽章取<b>最近完成</b>的若干条，且标题是<b>印尼语</b>（AC3：页面不得出现中文）。 */
    @Test
    void badgesUseIndonesianTitlesAndMostRecentFirst() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        stubOwner(0, 0, 3);
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt())).thenReturn(List.of());
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        stubMilestones(
                done("C-S1", now.minus(30, ChronoUnit.DAYS)),   // 最早
                done("C-S6", now.minus(1, ChronoUnit.DAYS)),    // 最近
                done("C-M3", now.minus(10, ChronoUnit.DAYS)));

        Model model = new ConcurrentModel();
        controller.card("TOK", model, req(), mock(HttpServletResponse.class));

        @SuppressWarnings("unchecked")
        List<String> badges = (List<String>) model.getAttribute("badges");
        // 最近完成的两条：C-S6（1 天前）、C-M3（10 天前）
        assertThat(badges).containsExactly("Mandi pertama", "Vaksinasi pertama");
        // 🛡 一个中文都不许有
        for (String b : badges) {
            assertThat(b.codePoints().noneMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF)).isTrue();
        }
    }

    /** 最新动态的相对时间来自最近完成的那条。 */
    @Test
    void latestMilestoneAgoComesFromMostRecentCompletion() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        stubOwner(0, 0, 2);
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt())).thenReturn(List.of());
        stubMilestones(
                done("C-S1", Instant.now().minus(40, ChronoUnit.DAYS)),
                done("C-S6", Instant.now().minus(3, ChronoUnit.DAYS)));

        Model model = new ConcurrentModel();
        controller.card("TOK", model, req(), mock(HttpServletResponse.class));

        assertThat(model.getAttribute("latestMilestoneAgo")).isEqualTo("3 HARI LALU");
    }

    /** 元信息行：四段齐全时按「品种 · 性别 · 年龄 · bersama 主人」拼。 */
    @Test
    void metaLineRendersAllSegments() {
        PetProfile p = profile();
        p.setSex(com.tailtopia.profile.domain.PetSex.FEMALE);
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(p));
        stubOwner(0, 0, 0);
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.card("TOK", model, req(), mock(HttpServletResponse.class));

        String meta = (String) model.getAttribute("metaLine");
        assertThat(meta).startsWith("Shiba · Betina · ").endsWith(" · bersama Aurel");
        assertThat(meta).doesNotContain("··");
    }

    /** 🛡 存量档案没有性别（Story 1.1 起可空、不回填）→ 该段跳过，不留空位。 */
    @Test
    void metaLineSkipsMissingSex() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile())); // sex 为 null
        stubOwner(0, 0, 0);
        when(contentService.findRecentGrowthMomentsByEventDate(eq(7L), eq(10L), anyInt())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.card("TOK", model, req(), mock(HttpServletResponse.class));

        String meta = (String) model.getAttribute("metaLine");
        assertThat(meta).doesNotContain("Jantan").doesNotContain("Betina");
        assertThat(meta).doesNotContain("··");
        assertThat(meta).startsWith("Shiba · ");
    }
}
