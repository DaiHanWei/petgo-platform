package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelinePageResponse;
import com.tailtopia.profile.service.HealthEventTimelineSource.HealthEventView;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3.1 · L0：统一游标锚点的编解码、归并后再截断、同日不跨页，以及
 * <b>AC4 跨页无丢失无重复回归断言</b>（用内存假数据源精确模拟 DB 语义，headless 可跑）。
 *
 * <p>本类锁定的是**重构前的现网缺陷**：游标用 createdAt、排序用 effectiveDate，两把尺子导致
 * 「补记旧日期的日记」跨页丢失或重复。任何后续改动若让 {@link #ac4_backdatedEntryAppearsExactlyOnceAcrossPages()}
 * 变红，即为该缺陷复发——<b>该断言常驻，不得删除或弱化</b>。
 */
class TimelineCursorMergeTest {

    private ProfileService profileService;
    private ContentService contentService;
    private HealthEventTimelineSource health;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HealthEventTimelineSource> healthProvider = Mockito.mock(ObjectProvider.class);
    private com.tailtopia.profile.repository.MilestoneCompletionRepository milestoneCompletions;
    private com.tailtopia.profile.repository.IdCardRepository idCards;
    private TimelineService service;

    /** 内存假数据集：模拟 DB 的锚点取数语义（严格小于锚点、按全局序倒排、源内不截断到页大小）。 */
    private final List<GrowthMomentView> moments = new ArrayList<>();
    private final List<HealthEventView> healthEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        profileService = Mockito.mock(ProfileService.class);
        contentService = Mockito.mock(ContentService.class);
        health = Mockito.mock(HealthEventTimelineSource.class);
        MilestoneService milestoneService = Mockito.mock(MilestoneService.class);
        com.tailtopia.profile.repository.HealthRecordRepository healthRecords =
                Mockito.mock(com.tailtopia.profile.repository.HealthRecordRepository.class);

        when(profileService.hasProfile(1L)).thenReturn(true);
        when(profileService.findByOwnerId(1L)).thenReturn(Optional.of(pet()));
        when(healthProvider.getIfAvailable()).thenReturn(health);

        // 假 content 源：复刻 ContentPostRepository 两路查询 + 归并的语义。
        when(contentService.findGrowthMomentsBeforeAnchor(eq(1L), anyLong(), Mockito.any(), Mockito.any(),
                Mockito.any(), anyInt()))
                .thenAnswer(inv -> {
                    LocalDate anchorDate = inv.getArgument(2);
                    Instant anchorKey = inv.getArgument(3);
                    Instant createdUpper = inv.getArgument(4);
                    int limit = inv.getArgument(5);
                    return moments.stream()
                            .filter(g -> g.eventDate() != null
                                    ? beforeAnchor(g.eventDate(), g.createdAt(), anchorDate, anchorKey)
                                    : g.createdAt().isBefore(createdUpper))
                            .sorted(Comparator.comparing(TimelineCursorMergeTest::effDate)
                                    .thenComparing(GrowthMomentView::createdAt).reversed())
                            .limit(limit)
                            .toList();
                });

        // 假健康源：只认 createdAt 上界（与真实实现一致）。
        when(health.recentHealthEvents(anyLong(), Mockito.any(), anyInt())).thenAnswer(inv -> {
            Instant before = inv.getArgument(1);
            int limit = inv.getArgument(2);
            return healthEvents.stream()
                    .filter(h -> h.createdAt().isBefore(before))
                    .sorted(Comparator.comparing(HealthEventView::createdAt).reversed())
                    .limit(limit)
                    .toList();
        });

                // Story 3.2 新增的两个源（本类不造它们的数据 → 返回空列表，等价于「只有内容 + 问诊存档」）。
        milestoneCompletions =
                Mockito.mock(com.tailtopia.profile.repository.MilestoneCompletionRepository.class);
        idCards = Mockito.mock(com.tailtopia.profile.repository.IdCardRepository.class);
        when(milestoneCompletions.findTimelineViewsBefore(anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        when(idCards.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        service = new TimelineService(profileService, contentService, healthProvider, milestoneService,
                healthRecords, milestoneCompletions, idCards);
    }

    // ===== 锚点编解码（AC1） =====

    @Test
    void anchorRoundTripsAndIsOpaque() {
        TimelineAnchor a = new TimelineAnchor(LocalDate.parse("2026-05-20"), Instant.parse("2026-06-01T08:30:00Z"));
        String encoded = a.encode();
        // 不可枚举：不得把日期/时刻明文外露
        assertThat(encoded).doesNotContain("2026-05-20").doesNotContain("2026-06-01");
        assertThat(TimelineAnchor.decode(encoded)).isEqualTo(a);
    }

    @Test
    void invalidCursorRejectedAs422() {
        assertThatThrownBy(() -> TimelineAnchor.decode("!!!not-base64!!!")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> TimelineAnchor.decode(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("no-separator".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(AppException.class);
    }

    /** 夹紧逻辑：补记场景（事件日期旧、创建时刻新）下，单键源的上界必须收到次日零点，而非原始 createdAt。 */
    @Test
    void createdAtUpperBoundClampsToAnchorDay() {
        TimelineAnchor backdated = new TimelineAnchor(
                LocalDate.parse("2026-05-01"), Instant.parse("2026-06-20T12:00:00Z"));
        assertThat(backdated.createdAtUpperBound()).isEqualTo(Instant.parse("2026-05-02T00:00:00Z"));

        TimelineAnchor sameDay = new TimelineAnchor(
                LocalDate.parse("2026-05-01"), Instant.parse("2026-05-01T09:00:00Z"));
        assertThat(sameDay.createdAtUpperBound()).isEqualTo(Instant.parse("2026-05-01T09:00:00Z"));
    }

    // ===== AC2 归并后再截断 =====

    @Test
    void mergesAcrossSourcesThenTruncates() {
        moments.add(momentEv(1, "2026-06-01T10:00:00Z", "2026-06-01"));
        moments.add(momentEv(2, "2026-06-03T10:00:00Z", "2026-06-03"));
        healthEvents.add(healthAt("2026-06-02T10:00:00Z"));

        TimelinePageResponse p1 = service.getTimeline(1L, null, 2);

        // 归并后全局倒序：6-03 快乐时刻 → 6-02 健康事件；截断到 2 条，第 3 条留下一页。
        assertThat(p1.items()).hasSize(2);
        assertThat(p1.items().get(0).postId()).isEqualTo(2L);
        assertThat(p1.items().get(1).kind()).isEqualTo(TimelineItemResponse.HEALTH_EVENT);
        assertThat(p1.hasMore()).isTrue();
    }

    // ===== AC3 同日不跨页 =====

    @Test
    void sameDayItemsNeverSplitAcrossPages() {
        // 6-05 有 3 条；页大小 2 → 本页必须把 6-05 整天带走（允许超出页大小）。
        moments.add(momentEv(1, "2026-06-05T09:00:00Z", "2026-06-05"));
        moments.add(momentEv(2, "2026-06-05T10:00:00Z", "2026-06-05"));
        moments.add(momentEv(3, "2026-06-05T11:00:00Z", "2026-06-05"));
        moments.add(momentEv(4, "2026-06-04T09:00:00Z", "2026-06-04"));

        TimelinePageResponse p1 = service.getTimeline(1L, null, 2);

        assertThat(p1.items()).hasSize(3); // 超出页大小，但 6-05 整天在同一页
        assertThat(p1.items()).allSatisfy(i -> assertThat(i.effectiveDate()).isEqualTo(LocalDate.parse("2026-06-05")));
        assertThat(p1.hasMore()).isTrue();

        TimelinePageResponse p2 = service.getTimeline(1L, p1.nextCursor(), 2);
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.items().get(0).postId()).isEqualTo(4L);
    }

    /** 页大小恰好等于某日条目数：边界正好落在日界，不应触发延伸。 */
    @Test
    void pageSizeExactlyMatchesDayCount() {
        moments.add(momentEv(1, "2026-06-05T09:00:00Z", "2026-06-05"));
        moments.add(momentEv(2, "2026-06-05T10:00:00Z", "2026-06-05"));
        moments.add(momentEv(3, "2026-06-04T09:00:00Z", "2026-06-04"));

        TimelinePageResponse p1 = service.getTimeline(1L, null, 2);

        assertThat(p1.items()).hasSize(2);
        assertThat(p1.items()).allSatisfy(i -> assertThat(i.effectiveDate()).isEqualTo(LocalDate.parse("2026-06-05")));
        assertThat(p1.hasMore()).isTrue();
    }

    /**
     * <b>AC3 结构性不变量</b>：任何一个自然日都不得出现在两个不同的页里。
     *
     * <p>这条断言是「某源恰好取满批次上限」缺陷的检测器——若截断逻辑把「批次边界」误判成
     * 「当天结束」，同日条目会被拆到两页，此断言立即变红（集合仍不丢不重，AC4 察觉不到）。
     * 数据刻意造成单日条目数远超页大小，并跨两个数据源。
     */
    @Test
    void ac3_noDayEverSplitAcrossPages() {
        // 06-05 一天塞 9 条快乐时刻 + 3 条健康事件 = 12 条，远超页大小
        for (int i = 1; i <= 9; i++) {
            moments.add(momentEv(i, String.format("2026-06-05T%02d:00:00Z", i), "2026-06-05"));
        }
        for (int i = 10; i <= 12; i++) {
            healthEvents.add(healthAt(String.format("2026-06-05T%02d:30:00Z", i)));
        }
        moments.add(momentEv(20, "2026-06-06T10:00:00Z", "2026-06-06"));
        moments.add(momentEv(21, "2026-06-04T10:00:00Z", "2026-06-04"));

        for (int pageSize : new int[] {1, 2, 3, 5}) {
            Set<LocalDate> daysSeen = new HashSet<>();
            List<TimelineItemResponse> all = new ArrayList<>();
            String cursor = null;
            for (int guard = 0; guard < 100; guard++) {
                TimelinePageResponse page = service.getTimeline(1L, cursor, pageSize);
                Set<LocalDate> daysInPage = new HashSet<>();
                page.items().forEach(i -> daysInPage.add(i.effectiveDate()));
                for (LocalDate d : daysInPage) {
                    assertThat(daysSeen)
                            .as("pageSize=%d：%s 这一天被拆到了两个页里（AC3 破）", pageSize, d)
                            .doesNotContain(d);
                }
                daysSeen.addAll(daysInPage);
                all.addAll(page.items());
                if (!page.hasMore() || page.nextCursor() == null) {
                    break;
                }
                cursor = page.nextCursor();
            }
            // 顺带复核不丢不重
            assertThat(all).as("pageSize=%d 总条数", pageSize).hasSize(14);
            assertThat(new HashSet<>(identities(all))).hasSize(14);
        }
    }

    // ===== AC4 跨页无丢失无重复（本 Story 核心验收，常驻） =====

    /**
     * <b>核心回归断言</b>：用户补记一篇旧日期的日记（event_date 上月、created_at 今天），
     * 逐页拉完整条时间线后，该条恰好出现一次，且全量集合与不分页查询完全相等。
     *
     * <p>重构前此断言必红：补记条在排序上落到旧位置、在翻页上被当作新记录 → 跨页丢失或重复。
     */
    @Test
    void ac4_backdatedEntryAppearsExactlyOnceAcrossPages() {
        // 正常序列：6-10 / 6-09 / 6-08 / 6-07
        moments.add(momentEv(10, "2026-06-10T10:00:00Z", "2026-06-10"));
        moments.add(momentEv(9, "2026-06-09T10:00:00Z", "2026-06-09"));
        moments.add(momentEv(8, "2026-06-08T10:00:00Z", "2026-06-08"));
        moments.add(momentEv(7, "2026-06-07T10:00:00Z", "2026-06-07"));
        // ⭐ 补记：事件日期是上月 5-15，但今天（6-11）才写 —— 缺陷触发条件
        moments.add(momentEv(99, "2026-06-11T23:00:00Z", "2026-05-15"));
        // 掺入健康事件，覆盖跨源
        healthEvents.add(healthAt("2026-06-09T15:00:00Z"));
        healthEvents.add(healthAt("2026-05-20T15:00:00Z"));

        List<TimelineItemResponse> paged = drainAllPages(2);
        List<TimelineItemResponse> unpaged = service.getTimeline(1L, null, 50).items();

        // ① 补记条恰好出现一次
        assertThat(paged.stream().filter(i -> i.postId() != null && i.postId() == 99L)).hasSize(1);
        // ② 位置在其事件日期对应的时序位置（5-20 健康事件之后、5-15 之前无条目）
        int idx = indexOfPost(paged, 99L);
        assertThat(paged.get(idx).effectiveDate()).isEqualTo(LocalDate.parse("2026-05-15"));
        assertThat(idx).isEqualTo(paged.size() - 1); // 最旧
        // ③ 逐页集合 == 不分页集合（无丢失、无重复）
        assertThat(identities(paged)).isEqualTo(identities(unpaged));
        assertThat(paged).hasSize(7);
        assertThat(new HashSet<>(identities(paged))).hasSize(7); // 无重复
    }

    /** 边界：跨月 + 同日多条 + 补记，逐页与不分页仍完全一致。 */
    @Test
    void ac4_boundaryAcrossMonthsAndMultiPerDay() {
        moments.add(momentEv(1, "2026-07-01T09:00:00Z", "2026-07-01"));
        moments.add(momentEv(2, "2026-06-30T09:00:00Z", "2026-06-30"));
        moments.add(momentEv(3, "2026-06-30T18:00:00Z", "2026-06-30"));
        moments.add(momentEv(4, "2026-06-30T21:00:00Z", "2026-06-30"));
        moments.add(momentEv(5, "2026-07-02T10:00:00Z", "2026-05-31")); // 补记，跨月
        healthEvents.add(healthAt("2026-06-30T12:00:00Z"));
        healthEvents.add(healthAt("2026-05-31T08:00:00Z"));

        for (int pageSize : new int[] {1, 2, 3, 5}) {
            List<TimelineItemResponse> paged = drainAllPages(pageSize);
            List<TimelineItemResponse> unpaged = service.getTimeline(1L, null, 50).items();
            assertThat(identities(paged))
                    .as("pageSize=%d 逐页结果必须与不分页完全一致", pageSize)
                    .isEqualTo(identities(unpaged));
            assertThat(new HashSet<>(identities(paged))).hasSize(paged.size());
        }
    }

    /** 存量兜底：event_date 为 NULL 的历史行（V26 加列未回填）不得从时间线上消失。 */
    @Test
    void legacyNullEventDateRowsStillAppear() {
        moments.add(new GrowthMomentView(1L, Instant.parse("2026-06-02T10:00:00Z"), null, List.of("u1"), "legacy",
                com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                com.tailtopia.content.domain.PostStatus.PUBLISHED));
        moments.add(momentEv(2, "2026-06-03T10:00:00Z", "2026-06-03"));

        List<TimelineItemResponse> all = drainAllPages(1);

        assertThat(identities(all)).containsExactly("P2", "P1");
    }

    // ===== helpers =====

    /** 逐页拉到底，返回拼接后的全量条目。带死循环保护。 */
    private List<TimelineItemResponse> drainAllPages(int pageSize) {
        List<TimelineItemResponse> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 100; guard++) {
            TimelinePageResponse page = service.getTimeline(1L, cursor, pageSize);
            all.addAll(page.items());
            if (!page.hasMore() || page.nextCursor() == null) {
                return all;
            }
            cursor = page.nextCursor();
        }
        throw new IllegalStateException("翻页未收敛——游标未推进（疑似锚点比较错误）");
    }

    /** 条目身份：快乐时刻取 P{postId}，健康事件取 H{createdAt}。用于集合比对。 */
    private static List<String> identities(List<TimelineItemResponse> items) {
        return items.stream()
                .map(i -> i.postId() != null ? "P" + i.postId() : "H" + i.date())
                .toList();
    }

    private static int indexOfPost(List<TimelineItemResponse> items, long postId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).postId() != null && items.get(i).postId() == postId) {
                return i;
            }
        }
        throw new AssertionError("未找到 postId=" + postId);
    }

    private static boolean beforeAnchor(LocalDate d, Instant k, LocalDate anchorDate, Instant anchorKey) {
        return d.isBefore(anchorDate) || (d.equals(anchorDate) && k.isBefore(anchorKey));
    }

    private static LocalDate effDate(GrowthMomentView g) {
        return g.eventDate() != null ? g.eventDate()
                : g.createdAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    private static GrowthMomentView momentEv(long id, String createdIso, String eventIso) {
        return new GrowthMomentView(id, Instant.parse(createdIso), LocalDate.parse(eventIso),
                List.of("u" + id), "m" + id,
                com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                com.tailtopia.content.domain.PostStatus.PUBLISHED);
    }

    private static HealthEventView healthAt(String iso) {
        return new HealthEventView(Instant.parse(iso), "GREEN", "摘要", "AI_TRIAGE", "t-" + iso);
    }

    private static PetProfile pet() {
        PetProfile p = PetProfile.create(1L, PetType.DOG, "Rocky", null, "Shiba", null, null, "TOK");
        try {
            java.lang.reflect.Field f = PetProfile.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, 1L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    @SuppressWarnings("unused")
    private static Set<String> unused() {
        return Set.of();
    }
}
