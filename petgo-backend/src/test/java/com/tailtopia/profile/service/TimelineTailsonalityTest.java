package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.place.service.PlaceCheckinTimelineQuery;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.CalendarMonthResponse;
import com.tailtopia.profile.dto.DayDetailResponse;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelineItemType;
import com.tailtopia.profile.dto.TimelinePageResponse;
import com.tailtopia.tailsonality.dto.TailsonalityTimelineView;
import com.tailtopia.tailsonality.service.TailsonalityTimelineQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * V1.3.2 batch-a Story 3.3 · AC6 · L0：Diary Tailsonality 条目 —— 能力闸（三处）、有效日期 = 解锁日、
 * 多次解锁多条、取满时降地板（跨页不漏不重）、日详情排序、日历格子。
 */
class TimelineTailsonalityTest {

    private static final long OWNER = 1L;
    private static final long PET = 1L;
    private static final TimelineCapabilities TS =
            TimelineCapabilities.of(TimelineCapabilities.Capability.TAILSONALITY);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HealthEventTimelineSource> healthProvider = Mockito.mock(ObjectProvider.class);
    private final ProfileService profileService = Mockito.mock(ProfileService.class);
    private final ContentService contentService = Mockito.mock(ContentService.class);
    private final com.tailtopia.profile.repository.HealthRecordRepository healthRecords =
            Mockito.mock(com.tailtopia.profile.repository.HealthRecordRepository.class);
    private final com.tailtopia.profile.repository.MilestoneCompletionRepository completions =
            Mockito.mock(com.tailtopia.profile.repository.MilestoneCompletionRepository.class);
    private final com.tailtopia.profile.repository.IdCardRepository idCards =
            Mockito.mock(com.tailtopia.profile.repository.IdCardRepository.class);
    private final PlaceCheckinTimelineQuery checkins = Mockito.mock(PlaceCheckinTimelineQuery.class);
    private final TailsonalityTimelineQuery unlocks = Mockito.mock(TailsonalityTimelineQuery.class);
    private TimelineService service;

    @BeforeEach
    void setUp() throws Exception {
        PetProfile p = PetProfile.create(OWNER, PetType.CAT, "Momo", null, null, null, null, "TOK");
        java.lang.reflect.Field f = PetProfile.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(p, PET);
        when(profileService.findByOwnerId(OWNER)).thenReturn(Optional.of(p));
        when(completions.findTimelineViewsBefore(anyLong(), any(), any())).thenReturn(List.of());
        when(idCards.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        when(contentService.findCheckinIdsWithTimelinePost(anyLong(), anyLong(), anyCollection())).thenReturn(Set.of());
        service = new TimelineService(profileService, contentService, healthProvider,
                Mockito.mock(MilestoneService.class), Mockito.mock(MilestoneCelebrationService.class),
                healthRecords, completions, idCards,
                Mockito.mock(com.tailtopia.content.service.ContentTagQueryService.class), checkins, unlocks);
    }

    private static TailsonalityTimelineView unlock(long id, String unlockedIso, String testedOn) {
        return new TailsonalityTimelineView(id, Instant.parse(unlockedIso), "tok" + id, "ENTJ-H",
                LocalDate.parse(testedOn));
    }

    @Test
    void withoutSupportsNothingIsQueriedAnywhere() {
        TimelinePageResponse page = service.getTimeline(OWNER, null, 20);
        assertThat(page.items()).noneMatch(i -> i.itemType() == TimelineItemType.TAILSONALITY_BANNER);
        service.getDayDetail(OWNER, LocalDate.of(2026, 9, 30));
        service.getCalendarMonth(OWNER, 2026, 9);
        // 只声明 place_checkin 也不取。
        service.getTimeline(OWNER, null, 20, TimelineCapabilities.of(TimelineCapabilities.Capability.PLACE_CHECKIN));
        Mockito.verifyNoInteractions(unlocks);
    }

    @Test
    void bannerUsesUnlockDayAsEffectiveDateAndCarriesTokenCodeAndTestedOn() {
        when(unlocks.findForPetBefore(eq(PET), any(), anyInt()))
                .thenReturn(List.of(unlock(5, "2026-09-30T23:00:00Z", "2026-09-01")));

        TimelineItemResponse b = service.getTimeline(OWNER, null, 20, TS).items().get(0);

        assertThat(b.itemType()).isEqualTo(TimelineItemType.TAILSONALITY_BANNER);
        assertThat(b.kind()).isEqualTo(TimelineItemResponse.TAILSONALITY);
        assertThat(b.eventDate()).as("有效日期 = 解锁时刻的 UTC 日，不是测试日").isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(b.date()).isEqualTo(Instant.parse("2026-09-30T23:00:00Z"));
        assertThat(b.tailsonalityResultToken()).isEqualTo("tok5");
        assertThat(b.tailsonalityCode()).isEqualTo("ENTJ-H");
        assertThat(b.tailsonalityTestedOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(b.postId()).isNull();
    }

    @Test
    void twoUnlocksTwoBannersOrderedWithOtherSources() {
        when(contentService.findGrowthMomentsBeforeAnchor(anyLong(), anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(new GrowthMomentView(1L, Instant.parse("2026-09-30T12:00:00Z"),
                        LocalDate.of(2026, 9, 30), List.of(), "m",
                        com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                        com.tailtopia.content.domain.PostStatus.PUBLISHED)));
        when(unlocks.findForPetBefore(eq(PET), any(), anyInt())).thenReturn(List.of(
                unlock(6, "2026-09-30T09:00:00Z", "2026-09-30"), unlock(5, "2026-09-28T09:00:00Z", "2026-09-01")));

        List<TimelineItemResponse> items = service.getTimeline(OWNER, null, 20, TS).items();

        assertThat(items).extracting(TimelineItemResponse::kind).containsExactly(
                TimelineItemResponse.TAILSONALITY, TimelineItemResponse.HAPPY_MOMENT, TimelineItemResponse.TAILSONALITY);
    }

    /** 2026-10-06「只看 Diary」：只剩主人自己发的内容；Tailsonality / 健康 / 身份证源连查都不查。 */
    @Test
    void diaryOnlyKeepsOwnPostsAndSkipsOtherSources() {
        when(contentService.findGrowthMomentsBeforeAnchor(anyLong(), anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(new GrowthMomentView(1L, Instant.parse("2026-09-30T12:00:00Z"),
                        LocalDate.of(2026, 9, 30), List.of(), "m",
                        com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                        com.tailtopia.content.domain.PostStatus.PUBLISHED)));
        when(unlocks.findForPetBefore(eq(PET), any(), anyInt())).thenReturn(List.of(
                unlock(6, "2026-09-30T09:00:00Z", "2026-09-30")));

        List<TimelineItemResponse> items = service.getTimeline(OWNER, null, 20, TS, true).items();

        assertThat(items).extracting(TimelineItemResponse::kind).containsExactly(TimelineItemResponse.HAPPY_MOMENT);
        verify(unlocks, never()).findForPetBefore(anyLong(), any(), anyInt());
        verify(idCards, never()).findByUserIdOrderByCreatedAtDesc(anyLong());
        // 不传 = false：同样数据照旧两条（老 App 行为不变）。
        assertThat(service.getTimeline(OWNER, null, 20, TS).items()).hasSize(2);
    }

    /** 取满时降地板：两页拼起来恰好是全集、不重不漏（同日多条不被拆开）。 */
    @Test
    void fullBatchesPageWithoutLossOrDuplicates() {
        List<TailsonalityTimelineView> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // 每天两条：同日多条 + 翻页边界。
            Instant day = Instant.parse("2026-09-30T20:00:00Z").minusSeconds(86_400L * (i / 2));
            all.add(unlock(100 - i, day.minusSeconds(60L * (i % 2)).toString(), "2026-09-01"));
        }
        when(unlocks.findForPetBefore(eq(PET), any(), anyInt())).thenAnswer(inv -> {
            Instant upper = inv.getArgument(1);
            int limit = inv.getArgument(2);
            return all.stream().filter(v -> v.unlockedAt().isBefore(upper)).limit(limit).toList();
        });

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            TimelinePageResponse page = service.getTimeline(OWNER, cursor, 5, TS);
            page.items().forEach(i -> seen.add(i.tailsonalityResultToken()));
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }
        assertThat(seen).hasSize(12).doesNotHaveDuplicates();
    }

    @Test
    void dayDetailPutsTailsonalityLast() {
        LocalDate d = LocalDate.of(2026, 9, 30);
        when(checkins.findForPetOnUtcDate(PET, d)).thenReturn(List.of(new com.tailtopia.place.dto.PlaceCheckinTimelineView(
                7L, Instant.parse("2026-09-30T20:00:00Z"), "p7", "Tempat", "ACTIVE")));
        when(unlocks.findForPetOnUtcDate(PET, d)).thenReturn(List.of(unlock(5, "2026-09-30T01:00:00Z", "2026-09-01")));

        DayDetailResponse r = service.getDayDetail(OWNER, d, TimelineCapabilities.parse(List.of("place_checkin,tailsonality")));

        assertThat(r.items()).extracting(TimelineItemResponse::kind)
                .containsExactly(TimelineItemResponse.PLACE_CHECKIN, TimelineItemResponse.TAILSONALITY);
    }

    @Test
    void calendarMarksUnlockDaysOnlyWhenDeclared() {
        when(contentService.findGrowthMomentsInMonth(anyLong(), anyLong(), any(), any()))
                .thenReturn(List.of(new GrowthMomentView(1L, Instant.parse("2026-09-05T02:00:00Z"),
                        LocalDate.of(2026, 9, 5), List.of("https://cdn/a.jpg"), "m",
                        com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                        com.tailtopia.content.domain.PostStatus.PUBLISHED)));
        when(unlocks.findForPetInUtcRange(eq(PET), eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 10, 1))))
                .thenReturn(List.of(unlock(5, "2026-09-05T03:00:00Z", "2026-09-01"),
                        unlock(6, "2026-09-12T03:00:00Z", "2026-09-10")));

        CalendarMonthResponse withCap = service.getCalendarMonth(OWNER, 2026, 9, TS);
        assertThat(withCap.days()).extracting(CalendarMonthResponse.DayCell::day).containsExactly(5, 12);
        CalendarMonthResponse.DayCell five = withCap.days().get(0);
        assertThat(five.hasTailsonality()).isTrue();
        assertThat(five.firstImageUrl()).as("既有维不被覆盖").isEqualTo("https://cdn/a.jpg");
        assertThat(five.hasHappyMoment()).isTrue();
        assertThat(withCap.days().get(1).hasTailsonality()).isTrue();

        CalendarMonthResponse without = service.getCalendarMonth(OWNER, 2026, 9);
        assertThat(without.days()).extracting(CalendarMonthResponse.DayCell::day).containsExactly(5);
        assertThat(without.days().get(0).hasTailsonality()).isNull();
    }

    @Test
    void capabilityParsesFromWire() {
        assertThat(TimelineCapabilities.parse(List.of("tailsonality")).tailsonality()).isTrue();
        assertThat(TimelineCapabilities.parse(List.of("place_checkin", "tailsonality")).placeCheckin()).isTrue();
        assertThat(TimelineCapabilities.parse(List.of("Tailsonality")).tailsonality()).isFalse();
        assertThat(TimelineCapabilities.none().tailsonality()).isFalse();
    }
}
