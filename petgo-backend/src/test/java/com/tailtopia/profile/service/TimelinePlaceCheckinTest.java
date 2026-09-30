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
import com.tailtopia.place.dto.PlaceCheckinTimelineView;
import com.tailtopia.place.service.PlaceCheckinTimelineQuery;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.CalendarMonthResponse;
import com.tailtopia.profile.dto.DayDetailResponse;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelineItemType;
import com.tailtopia.profile.dto.TimelinePageResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * V1.3.2 batch-a Story 1.6 · L0：Diary 打卡条目（AD-9）—— 能力闸、二选一去重、跨页不重、日详情 / 日历。
 */
class TimelinePlaceCheckinTest {

    private static final long OWNER = 1L;
    private static final long PET = 1L;
    private static final TimelineCapabilities CHECKIN =
            TimelineCapabilities.of(TimelineCapabilities.Capability.PLACE_CHECKIN);

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
                Mockito.mock(com.tailtopia.content.service.ContentTagQueryService.class), checkins);
    }

    private static PlaceCheckinTimelineView checkin(long id, String iso, String status) {
        return new PlaceCheckinTimelineView(id, Instant.parse(iso), "tok" + id, "Tempat " + id, status);
    }

    private static GrowthMomentView moment(long id, String createdIso, String eventDate) {
        return new GrowthMomentView(id, Instant.parse(createdIso), LocalDate.parse(eventDate), List.of(), "m" + id,
                com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                com.tailtopia.content.domain.PostStatus.PUBLISHED);
    }

    @Test
    void withoutSupportsNoBannerAndTheSourceIsNeverQueried() {
        TimelinePageResponse page = service.getTimeline(OWNER, null, 20);

        assertThat(page.items()).noneMatch(i -> i.itemType() == TimelineItemType.PLACE_CHECKIN_BANNER);
        Mockito.verifyNoInteractions(checkins);
        service.getDayDetail(OWNER, LocalDate.of(2026, 9, 30));
        service.getCalendarMonth(OWNER, 2026, 9);
        Mockito.verifyNoInteractions(checkins);
    }

    @Test
    void withSupportsTheBannerCarriesPlaceAndUtcEventDate() {
        // WIB 2026-10-01 06:00 = UTC 2026-09-30 23:00 → 有效日期是 UTC 的 9/30（与服务端 effectiveDate 同一天）。
        when(checkins.findForPetBefore(eq(PET), any(), anyInt()))
                .thenReturn(List.of(checkin(7, "2026-09-30T23:00:00Z", "ACTIVE")));

        TimelineItemResponse b = service.getTimeline(OWNER, null, 20, CHECKIN).items().get(0);

        assertThat(b.itemType()).isEqualTo(TimelineItemType.PLACE_CHECKIN_BANNER);
        assertThat(b.kind()).isEqualTo(TimelineItemResponse.PLACE_CHECKIN);
        assertThat(b.eventDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(b.checkinPlace()).isEqualTo(new TimelineItemResponse.CheckinPlace("tok7", "Tempat 7", "ACTIVE"));
        assertThat(b.postId()).isNull();
    }

    /** 二选一：有会进作者时间线的 GROWTH_MOMENT 关联帖 → 不出条目、只出帖子。 */
    @Test
    void checkinWithATimelinePostIsSuppressed() {
        when(checkins.findForPetBefore(eq(PET), any(), anyInt())).thenReturn(List.of(
                checkin(7, "2026-09-30T10:00:00Z", "ACTIVE"), checkin(8, "2026-09-29T10:00:00Z", "ACTIVE")));
        when(contentService.findCheckinIdsWithTimelinePost(eq(OWNER), eq(PET), anyCollection())).thenReturn(Set.of(7L));

        List<TimelineItemResponse> items = service.getTimeline(OWNER, null, 20, CHECKIN).items();

        assertThat(items).filteredOn(i -> i.itemType() == TimelineItemType.PLACE_CHECKIN_BANNER)
                .extracting(i -> i.checkinPlace().placeToken()).containsExactly("tok8");
    }

    /**
     * 跨页不重：去重按「关联帖会不会出现在这条时间线」判，与分页无关 —— 帖子（事件日期更早）落在别的页，
     * 打卡所在这一页仍不出条目。
     */
    @Test
    void dedupeDoesNotDependOnThePostBeingOnTheSamePage() {
        // 本页只有打卡（帖子事件日期改到了一个月前，不在本页取数范围内）。
        when(checkins.findForPetBefore(eq(PET), any(), anyInt()))
                .thenReturn(List.of(checkin(7, "2026-09-30T10:00:00Z", "ACTIVE")));
        when(contentService.findCheckinIdsWithTimelinePost(eq(OWNER), eq(PET), anyCollection())).thenReturn(Set.of(7L));

        assertThat(service.getTimeline(OWNER, null, 20, CHECKIN).items()).isEmpty();
    }

    @Test
    void sameDayMultiSourceOrderingAndCursorStability() {
        when(contentService.findGrowthMomentsBeforeAnchor(anyLong(), anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(moment(1, "2026-09-30T12:00:00Z", "2026-09-30")));
        when(checkins.findForPetBefore(eq(PET), any(), anyInt()))
                .thenReturn(List.of(checkin(7, "2026-09-30T09:00:00Z", "ACTIVE")));

        List<TimelineItemResponse> items = service.getTimeline(OWNER, null, 20, CHECKIN).items();

        // 同日按发布 / 完成时刻正序：09:00 打卡在前，12:00 帖子在后。
        assertThat(items).extracting(TimelineItemResponse::kind)
                .containsExactly(TimelineItemResponse.PLACE_CHECKIN, TimelineItemResponse.HAPPY_MOMENT);
    }

    /** 取满时照源④更新地板（用去重前的原始末条）：分页不会把同日条目拆开。 */
    @Test
    void aFullCheckinBatchLowersTheTrustedFloor() {
        when(checkins.findForPetBefore(eq(PET), any(), anyInt())).thenAnswer(inv -> {
            int limit = inv.getArgument(2);
            List<PlaceCheckinTimelineView> out = new java.util.ArrayList<>();
            for (int i = 0; i < limit; i++) {
                out.add(checkin(1000 - i, Instant.parse("2026-09-30T20:00:00Z").minusSeconds(3600L * 24 * i).toString(),
                        "ACTIVE"));
            }
            return out;
        });

        TimelinePageResponse page = service.getTimeline(OWNER, null, 5, CHECKIN);

        assertThat(page.items()).hasSize(5);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void dayDetailPutsCheckinsAfterHealthRecords() {
        LocalDate d = LocalDate.of(2026, 9, 30);
        when(contentService.findGrowthMomentsOnDate(OWNER, PET, d)).thenReturn(List.of(moment(1, "2026-09-30T12:00:00Z", "2026-09-30")));
        when(checkins.findForPetOnUtcDate(PET, d)).thenReturn(List.of(checkin(7, "2026-09-30T01:00:00Z", "UNAVAILABLE")));

        DayDetailResponse r = service.getDayDetail(OWNER, d, CHECKIN);

        assertThat(r.items()).extracting(TimelineItemResponse::kind)
                .containsExactly(TimelineItemResponse.HAPPY_MOMENT, TimelineItemResponse.PLACE_CHECKIN);
        assertThat(r.items().get(1).checkinPlace().status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void calendarMarksCheckinDaysOnlyWhenDeclaredAndNotDeduped() {
        when(contentService.findGrowthMomentsInMonth(anyLong(), anyLong(), any(), any()))
                .thenReturn(List.of(new GrowthMomentView(1L, Instant.parse("2026-09-05T02:00:00Z"),
                        LocalDate.of(2026, 9, 5), List.of("https://cdn/a.jpg"), "m",
                        com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                        com.tailtopia.content.domain.PostStatus.PUBLISHED)));
        when(checkins.findForPetInUtcRange(eq(PET), eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 10, 1))))
                .thenReturn(List.of(checkin(7, "2026-09-05T03:00:00Z", "ACTIVE"),
                        checkin(8, "2026-09-12T03:00:00Z", "ACTIVE"),
                        checkin(9, "2026-09-20T03:00:00Z", "ACTIVE")));
        when(contentService.findCheckinIdsWithTimelinePost(eq(OWNER), eq(PET), anyCollection())).thenReturn(Set.of(9L));

        CalendarMonthResponse m = service.getCalendarMonth(OWNER, 2026, 9, CHECKIN);

        assertThat(m.days()).extracting(CalendarMonthResponse.DayCell::day).containsExactly(5, 12);
        CalendarMonthResponse.DayCell five = m.days().get(0);
        assertThat(five.firstImageUrl()).isEqualTo("https://cdn/a.jpg");
        assertThat(five.hasPlaceCheckin()).isTrue();
        CalendarMonthResponse.DayCell twelve = m.days().get(1);
        assertThat(twelve.hasHappyMoment()).isFalse();
        assertThat(twelve.hasPlaceCheckin()).isTrue();

        // 未声明能力：不新建格子、字段为 null。
        CalendarMonthResponse old = service.getCalendarMonth(OWNER, 2026, 9);
        assertThat(old.days()).extracting(CalendarMonthResponse.DayCell::day).containsExactly(5);
        assertThat(old.days().get(0).hasPlaceCheckin()).isNull();
        verify(checkins, Mockito.times(1)).findForPetInUtcRange(anyLong(), any(), any());
    }

    @Test
    void capabilitiesParseBothStylesAndIgnoreUnknownOrMiscased() {
        assertThat(TimelineCapabilities.parse(List.of("place_checkin")).placeCheckin()).isTrue();
        assertThat(TimelineCapabilities.parse(List.of("tailsonality,place_checkin")).placeCheckin()).isTrue();
        assertThat(TimelineCapabilities.parse(List.of("PLACE_CHECKIN")).placeCheckin()).isFalse();
        assertThat(TimelineCapabilities.parse(List.of("nope")).placeCheckin()).isFalse();
        assertThat(TimelineCapabilities.parse(null).placeCheckin()).isFalse();
        verify(checkins, never()).findForPetBefore(anyLong(), any(), anyInt());
    }
}
