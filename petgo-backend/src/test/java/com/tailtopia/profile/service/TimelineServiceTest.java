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
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.dto.CalendarMonthResponse;
import com.tailtopia.profile.dto.DayDetailResponse;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelinePageResponse;
import com.tailtopia.profile.service.HealthEventTimelineSource.HealthEventView;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/** L0：聚合倒序 + 空健康源稳健 + 跨源合并 + 游标分页（AC1）。 */
class TimelineServiceTest {

    private ProfileService profileService;
    private ContentService contentService;
    private MilestoneService milestoneService;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HealthEventTimelineSource> healthProvider = Mockito.mock(ObjectProvider.class);
    private TimelineService service;

    @BeforeEach
    void setUp() {
        profileService = Mockito.mock(ProfileService.class);
        contentService = Mockito.mock(ContentService.class);
        milestoneService = Mockito.mock(MilestoneService.class);
        when(profileService.hasProfile(1L)).thenReturn(true);
        service = new TimelineService(profileService, contentService, healthProvider, milestoneService);
    }

    private GrowthMomentView moment(long id, String iso) {
        return new GrowthMomentView(id, Instant.parse(iso), null, List.of("u" + id), "moment" + id);
    }

    private GrowthMomentView momentEv(long id, String createdIso, String eventIso, String img) {
        return new GrowthMomentView(id, Instant.parse(createdIso), LocalDate.parse(eventIso),
                List.of(img), "moment" + id);
    }

    private static PetProfile pet(PetType type) {
        PetProfile p = PetProfile.create(1L, type, "Rocky", null, "Shiba", null, null, "TOK");
        try { // 模拟落库回填自增 id（getStats 取 profile.getId() 传里程碑进度查询）。
            java.lang.reflect.Field f = PetProfile.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, 1L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    @Test
    void noProfileThrows404() {
        when(profileService.hasProfile(9L)).thenReturn(false);
        assertThatThrownBy(() -> service.getTimeline(9L, null, 20)).isInstanceOf(AppException.class);
    }

    @Test
    void happyMomentsOnlyWhenHealthSourceAbsent() {
        when(healthProvider.getIfAvailable()).thenReturn(null); // 2.4 期无健康源
        when(contentService.findGrowthMoments(eq(1L), Mockito.any(), anyInt()))
                .thenReturn(List.of(moment(2, "2026-06-02T10:00:00Z"), moment(1, "2026-06-01T10:00:00Z")));

        TimelinePageResponse resp = service.getTimeline(1L, null, 20);

        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).kind()).isEqualTo(TimelineItemResponse.HAPPY_MOMENT);
        assertThat(resp.items().get(0).postId()).isEqualTo(2L); // 倒序：最新在前
        assertThat(resp.hasMore()).isFalse();
    }

    @Test
    void mergesAndSortsHappyAndHealthDesc() {
        HealthEventTimelineSource health = Mockito.mock(HealthEventTimelineSource.class);
        when(healthProvider.getIfAvailable()).thenReturn(health);
        when(contentService.findGrowthMoments(eq(1L), Mockito.any(), anyInt()))
                .thenReturn(List.of(moment(1, "2026-06-01T10:00:00Z")));
        when(health.recentHealthEvents(anyLong(), Mockito.any(), anyInt()))
                .thenReturn(List.of(new HealthEventView(Instant.parse("2026-06-03T10:00:00Z"), "YELLOW", "咳嗽", "AI_TRIAGE", "triage-1")));

        TimelinePageResponse resp = service.getTimeline(1L, null, 20);

        assertThat(resp.items()).hasSize(2);
        // 健康事件(6-03) 在快乐时刻(6-01) 之前
        assertThat(resp.items().get(0).kind()).isEqualTo(TimelineItemResponse.HEALTH_EVENT);
        assertThat(resp.items().get(0).aiLevel()).isEqualTo("YELLOW");
        assertThat(resp.items().get(1).kind()).isEqualTo(TimelineItemResponse.HAPPY_MOMENT);
    }

    @Test
    void hasMoreAndNextCursorWhenOverLimit() {
        when(healthProvider.getIfAvailable()).thenReturn(null);
        when(contentService.findGrowthMoments(eq(1L), Mockito.any(), anyInt()))
                .thenReturn(List.of(
                        moment(3, "2026-06-03T10:00:00Z"),
                        moment(2, "2026-06-02T10:00:00Z"),
                        moment(1, "2026-06-01T10:00:00Z")));

        TimelinePageResponse resp = service.getTimeline(1L, null, 2);

        assertThat(resp.items()).hasSize(2);
        assertThat(resp.hasMore()).isTrue();
        assertThat(resp.nextCursor()).isEqualTo("2026-06-02T10:00:00Z");
    }

    @Test
    void invalidCursorRejected() {
        assertThatThrownBy(() -> service.getTimeline(1L, "not-a-time", 20))
                .isInstanceOf(AppException.class);
    }

    // ===== R2 · AC6 时间线按 event_date 排序 =====

    @Test
    void timelineSortsByEventDateNotCreatedAt() {
        when(healthProvider.getIfAvailable()).thenReturn(null);
        // id=1 较晚创建但事件日期更早；id=2 较早创建但事件日期更晚 → 应按 event_date 倒序：2 在前。
        when(contentService.findGrowthMoments(eq(1L), Mockito.any(), anyInt())).thenReturn(List.of(
                momentEv(1, "2026-06-05T10:00:00Z", "2024-01-01", "a"),
                momentEv(2, "2026-06-04T10:00:00Z", "2024-12-31", "b")));

        TimelinePageResponse resp = service.getTimeline(1L, null, 20);

        assertThat(resp.items().get(0).postId()).isEqualTo(2L); // event_date 更晚在前
        assertThat(resp.items().get(0).eventDate()).isEqualTo(LocalDate.parse("2024-12-31"));
        assertThat(resp.items().get(1).postId()).isEqualTo(1L);
    }

    // ===== R2 · AC5/AC6 日历月视图 =====

    @Test
    void calendarAggregatesByEventDateWithEarliestImageAndHealthBadge() {
        when(profileService.findByOwnerId(1L)).thenReturn(Optional.of(pet(PetType.DOG)));
        HealthEventTimelineSource health = Mockito.mock(HealthEventTimelineSource.class);
        when(healthProvider.getIfAvailable()).thenReturn(health);
        // 同一天 6/2 两条：先 created 的 img2a 应为格子首图；6/10 一条。
        when(contentService.findGrowthMomentsInMonth(eq(1L), Mockito.any(), Mockito.any())).thenReturn(List.of(
                momentEv(10, "2026-06-02T08:00:00Z", "2026-06-02", "img2a"),
                momentEv(11, "2026-06-02T09:00:00Z", "2026-06-02", "img2b"),
                momentEv(12, "2026-06-10T09:00:00Z", "2026-06-10", "img10")));
        // 健康事件 6/2（叠加角标）+ 6/20（独立 🏥 日）。
        when(health.healthEventsInRange(eq(1L), Mockito.any(), Mockito.any())).thenReturn(List.of(
                new HealthEventView(Instant.parse("2026-06-02T12:00:00Z"), "GREEN", "x", "AI_TRIAGE", "triage-x"),
                new HealthEventView(Instant.parse("2026-06-20T12:00:00Z"), "YELLOW", "y", "AI_TRIAGE", "triage-y")));

        CalendarMonthResponse resp = service.getCalendarMonth(1L, 2026, 6);

        assertThat(resp.days()).hasSize(3); // 6/2, 6/10, 6/20
        var d2 = resp.days().get(0);
        assertThat(d2.day()).isEqualTo(2);
        assertThat(d2.firstImageUrl()).isEqualTo("img2a"); // 最早 created_at 首图
        assertThat(d2.hasHappyMoment()).isTrue();
        assertThat(d2.hasHealthEvent()).isTrue();
        var d20 = resp.days().get(2);
        assertThat(d20.day()).isEqualTo(20);
        assertThat(d20.hasHappyMoment()).isFalse();
        assertThat(d20.hasHealthEvent()).isTrue();
    }

    // ===== R2 · AC6 当天详情（created_at 正序） =====

    @Test
    void dayDetailMergesAndSortsByCreatedAtAsc() {
        when(profileService.findByOwnerId(1L)).thenReturn(Optional.of(pet(PetType.CAT)));
        HealthEventTimelineSource health = Mockito.mock(HealthEventTimelineSource.class);
        when(healthProvider.getIfAvailable()).thenReturn(health);
        when(contentService.findGrowthMomentsOnDate(eq(1L), eq(LocalDate.parse("2026-06-02"))))
                .thenReturn(List.of(momentEv(10, "2026-06-02T08:00:00Z", "2026-06-02", "a")));
        when(health.healthEventsOnDay(eq(1L), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(new HealthEventView(Instant.parse("2026-06-02T07:00:00Z"), "GREEN", "z", "AI_TRIAGE", "triage-z")));

        DayDetailResponse resp = service.getDayDetail(1L, LocalDate.parse("2026-06-02"));

        assertThat(resp.items()).hasSize(2);
        // created_at 正序：07:00 健康事件在前，08:00 快乐时刻在后。
        assertThat(resp.items().get(0).kind()).isEqualTo(TimelineItemResponse.HEALTH_EVENT);
        assertThat(resp.items().get(1).kind()).isEqualTo(TimelineItemResponse.HAPPY_MOMENT);
    }

    // ===== R2 · AC5 统计栏 =====

    @Test
    void statsCountsAndMilestoneProgressByPetType() {
        when(profileService.findByOwnerId(1L)).thenReturn(Optional.of(pet(PetType.DOG)));
        HealthEventTimelineSource health = Mockito.mock(HealthEventTimelineSource.class);
        when(healthProvider.getIfAvailable()).thenReturn(health);
        when(contentService.countGrowthMoments(1L)).thenReturn(7L);
        when(health.countHealthEvents(1L)).thenReturn(3L);
        // 8.2 连带：里程碑改真供数（接 8.1 roster + completions）。
        when(milestoneService.getProgress(anyLong(), eq(PetType.DOG)))
                .thenReturn(new MilestoneService.MilestoneProgress(4L, 30));

        ArchiveStatsResponse resp = service.getStats(1L);

        assertThat(resp.happyMomentCount()).isEqualTo(7L);
        assertThat(resp.consultCount()).isEqualTo(3L);
        assertThat(resp.milestoneCompleted()).isEqualTo(4L); // 真计数
        assertThat(resp.milestoneTotal()).isEqualTo(30); // 狗 = 30
    }

    @Test
    void statsMilestoneTotalForOtherPetIs15() {
        when(profileService.findByOwnerId(1L)).thenReturn(Optional.of(pet(PetType.OTHER)));
        when(healthProvider.getIfAvailable()).thenReturn(null);
        when(contentService.countGrowthMoments(1L)).thenReturn(0L);
        when(milestoneService.getProgress(anyLong(), eq(PetType.OTHER)))
                .thenReturn(new MilestoneService.MilestoneProgress(0L, 15));

        ArchiveStatsResponse resp = service.getStats(1L);
        assertThat(resp.milestoneTotal()).isEqualTo(15);
        assertThat(resp.consultCount()).isZero(); // 无健康源 → 0
    }

    @Test
    void calendarNoProfileThrows404() {
        when(profileService.findByOwnerId(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getCalendarMonth(9L, 2026, 6)).isInstanceOf(AppException.class);
    }
}
