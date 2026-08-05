package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.CalendarMonthResponse;
import com.tailtopia.profile.dto.DayDetailResponse;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.service.HealthEventTimelineSource.HealthEventView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3.4 · L0：日历格子新增「当天健康记录条数」一维 + 当天详情三源与大类排序。
 *
 * <p>两条要点：
 * <ul>
 *   <li>后端**只加一维**（`healthRecordCount`），已有五维原样复用 —— 断言旧维度值不变；</li>
 *   <li>当天详情**先补第三个数据源**（结构化健康记录）再改排序 —— 只改排序会整类漏掉。</li>
 * </ul>
 */
class CalendarAndDayDetailStory34Test {

    private ProfileService profileService;
    private ContentService contentService;
    private HealthRecordRepository healthRecords;
    private HealthEventTimelineSource health;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HealthEventTimelineSource> healthProvider = Mockito.mock(ObjectProvider.class);
    private TimelineService service;

    private static final LocalDate DAY = LocalDate.parse("2026-06-02");

    @BeforeEach
    void setUp() {
        profileService = Mockito.mock(ProfileService.class);
        contentService = Mockito.mock(ContentService.class);
        healthRecords = Mockito.mock(HealthRecordRepository.class);
        health = Mockito.mock(HealthEventTimelineSource.class);
        MilestoneService milestoneService = Mockito.mock(MilestoneService.class);
        MilestoneCompletionRepository completions = Mockito.mock(MilestoneCompletionRepository.class);
        IdCardRepository idCards = Mockito.mock(IdCardRepository.class);
        when(healthProvider.getIfAvailable()).thenReturn(health);
        service = new TimelineService(profileService, contentService, healthProvider, milestoneService,
                healthRecords, completions, idCards);

        PetProfile profile = PetProfile.create(1L, PetType.CAT, "Momo", null, null, null, null, "tok");
        java.lang.reflect.Field id;
        try {
            id = PetProfile.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(profile, 9L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        when(profileService.findByOwnerId(1L)).thenReturn(Optional.of(profile));
    }

    private static HealthRecord record(HealthRecordType type, String createdAt) {
        HealthRecord r = HealthRecord.create(9L, type, null, null, DAY, "备注");
        try {
            java.lang.reflect.Field c = HealthRecord.class.getDeclaredField("createdAt");
            c.setAccessible(true);
            c.set(r, Instant.parse(createdAt));
            java.lang.reflect.Field i = HealthRecord.class.getDeclaredField("id");
            i.setAccessible(true);
            i.set(r, 7L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return r;
    }

    // ===== AC1 只加一维：条数 =====

    @Test
    void calendarCountsHealthRecordsPerDay_andKeepsExistingDimensions() {
        when(contentService.findGrowthMomentsInMonth(eq(1L), anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        when(health.healthEventsInRange(eq(1L), Mockito.any(), Mockito.any())).thenReturn(List.of());
        when(healthRecords.findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
                anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(record(HealthRecordType.VACCINE, "2026-06-02T08:00:00Z"),
                        record(HealthRecordType.DEWORM, "2026-06-02T09:00:00Z")));

        CalendarMonthResponse resp = service.getCalendarMonth(1L, 2026, 6);

        assertThat(resp.days()).singleElement()
                .returns(2, CalendarMonthResponse.DayCell::healthRecordCount)
                // 首条决定 type（既有维度行为不变）
                .returns("VACCINE", CalendarMonthResponse.DayCell::healthRecordType)
                .returns(false, CalendarMonthResponse.DayCell::hasHappyMoment)
                .returns(false, CalendarMonthResponse.DayCell::hasHealthEvent)
                .returns(null, CalendarMonthResponse.DayCell::firstImageUrl);
    }

    @Test
    void calendarKeepsDiaryImageWhenDayAlsoHasHealthRecord_andStillCounts() {
        // AD-16：日历是「整天取一个代表标记」，diary 图优先；条数仍照实累计供前端判定④。
        when(contentService.findGrowthMomentsInMonth(eq(1L), anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(new GrowthMomentView(1L, Instant.parse("2026-06-02T07:00:00Z"),
                        DAY, List.of("https://x/1.jpg"), "文字")));
        when(health.healthEventsInRange(eq(1L), Mockito.any(), Mockito.any())).thenReturn(List.of());
        when(healthRecords.findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
                anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(record(HealthRecordType.VACCINE, "2026-06-02T08:00:00Z")));

        CalendarMonthResponse resp = service.getCalendarMonth(1L, 2026, 6);

        assertThat(resp.days()).singleElement()
                .returns("https://x/1.jpg", CalendarMonthResponse.DayCell::firstImageUrl)
                .returns(true, CalendarMonthResponse.DayCell::hasHappyMoment)
                .returns(1, CalendarMonthResponse.DayCell::healthRecordCount);
    }

    // ===== AC5 当天详情：先补第三源，再按大类排序 =====

    @Test
    void dayDetailIncludesStructuredHealthRecords_asThirdSource() {
        when(contentService.findGrowthMomentsOnDate(eq(1L), anyLong(), eq(DAY)))
                .thenReturn(List.of(new GrowthMomentView(1L, Instant.parse("2026-06-02T10:00:00Z"),
                        DAY, List.of(), "文字")));
        when(health.healthEventsOnDay(eq(1L), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(new HealthEventView(Instant.parse("2026-06-02T09:00:00Z"),
                        "GREEN", "摘要", "AI_TRIAGE", "triage:1")));
        when(healthRecords.findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
                anyLong(), eq(DAY), eq(DAY)))
                .thenReturn(List.of(record(HealthRecordType.VACCINE, "2026-06-02T08:00:00Z")));

        DayDetailResponse resp = service.getDayDetail(1L, DAY);

        // 三源齐全
        assertThat(resp.items()).hasSize(3);
        // 大类顺序：diary > 问诊 > 健康记录（**与时间先后无关**：diary 10:00 仍排最前）
        assertThat(resp.items().stream().map(TimelineItemResponse::kind).toList())
                .containsExactly(TimelineItemResponse.HAPPY_MOMENT, TimelineItemResponse.HEALTH_EVENT,
                        TimelineItemResponse.HEALTH_RECORD);
    }

    @Test
    void dayDetailSortsWithinCategoryByTimeAscending() {
        when(contentService.findGrowthMomentsOnDate(eq(1L), anyLong(), eq(DAY)))
                .thenReturn(List.of(
                        new GrowthMomentView(2L, Instant.parse("2026-06-02T11:00:00Z"), DAY, List.of(), "晚"),
                        new GrowthMomentView(1L, Instant.parse("2026-06-02T09:00:00Z"), DAY, List.of(), "早")));
        when(health.healthEventsOnDay(eq(1L), Mockito.any(), Mockito.any())).thenReturn(List.of());
        when(healthRecords.findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
                anyLong(), eq(DAY), eq(DAY))).thenReturn(List.of());

        DayDetailResponse resp = service.getDayDetail(1L, DAY);

        assertThat(resp.items().stream().map(TimelineItemResponse::postId).toList())
                .containsExactly(1L, 2L); // 类内按时间正序
    }
}
