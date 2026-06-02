package com.petgo.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.petgo.content.service.ContentService;
import com.petgo.content.service.GrowthMomentView;
import com.petgo.profile.dto.TimelineItemResponse;
import com.petgo.profile.dto.TimelinePageResponse;
import com.petgo.profile.service.HealthEventTimelineSource.HealthEventView;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/** L0：聚合倒序 + 空健康源稳健 + 跨源合并 + 游标分页（AC1）。 */
class TimelineServiceTest {

    private ProfileService profileService;
    private ContentService contentService;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HealthEventTimelineSource> healthProvider = Mockito.mock(ObjectProvider.class);
    private TimelineService service;

    @BeforeEach
    void setUp() {
        profileService = Mockito.mock(ProfileService.class);
        contentService = Mockito.mock(ContentService.class);
        when(profileService.hasProfile(1L)).thenReturn(true);
        service = new TimelineService(profileService, contentService, healthProvider);
    }

    private GrowthMomentView moment(long id, String iso) {
        return new GrowthMomentView(id, Instant.parse(iso), List.of("u" + id), "moment" + id);
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
                .thenReturn(List.of(new HealthEventView(Instant.parse("2026-06-03T10:00:00Z"), "YELLOW", "咳嗽")));

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
}
