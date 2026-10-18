package com.tailtopia.profile.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * L0（V1.4.0 multi-pet AC5）：同账号两只宠物 A（默认）/ B —— 带 petId=B 时内容读口、健康源、健康记录、
 * 里程碑、打卡 / 性格 banner 读口都以 B 调用；不带 petId = A。
 */
class TimelineMultiPetTest {

    private static final long OWNER = 1L;
    private static final long A = 10L;
    private static final long B = 22L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TimelineCapabilities ALL = TimelineCapabilities.of(
            TimelineCapabilities.Capability.values());

    private final PetResolver resolver = mock(PetResolver.class);
    private final ContentService content = mock(ContentService.class);
    private final HealthEventTimelineSource health = mock(HealthEventTimelineSource.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HealthEventTimelineSource> healthProvider = mock(ObjectProvider.class);
    private final MilestoneService milestoneService = mock(MilestoneService.class);
    private final HealthRecordRepository healthRecords = mock(HealthRecordRepository.class);
    private final MilestoneCompletionRepository completions = mock(MilestoneCompletionRepository.class);
    private final IdCardRepository idCards = mock(IdCardRepository.class);
    private final com.tailtopia.place.service.PlaceCheckinTimelineQuery checkins =
            mock(com.tailtopia.place.service.PlaceCheckinTimelineQuery.class);
    private final com.tailtopia.tailsonality.service.TailsonalityTimelineQuery tailsonality =
            mock(com.tailtopia.tailsonality.service.TailsonalityTimelineQuery.class);
    private TimelineService service;

    @BeforeEach
    void setUp() {
        PetProfile a = pet(OWNER, A, T0);
        PetProfile b = pet(OWNER, B, T0.plusSeconds(60));
        when(resolver.resolve(OWNER, null)).thenReturn(a);
        when(resolver.resolve(OWNER, A)).thenReturn(a);
        when(resolver.resolve(OWNER, B)).thenReturn(b);
        when(healthProvider.getIfAvailable()).thenReturn(health);
        when(milestoneService.getProgress(anyLong(), any())).thenReturn(new MilestoneService.MilestoneProgress(0, 0));
        service = new TimelineService(resolver, content, healthProvider, milestoneService,
                mock(MilestoneCelebrationService.class), healthRecords, completions, idCards,
                mock(com.tailtopia.content.service.ContentTagQueryService.class), checkins, tailsonality);
    }

    @Test
    void timelineWithPetIdBQueriesEverySourceWithB() {
        service.getTimeline(OWNER, B, null, 20, ALL, false);

        verify(content).findGrowthMomentsBeforeAnchor(eq(OWNER), eq(B), any(), any(), any(), anyInt());
        verify(health).recentHealthEvents(eq(B), any(), any(), anyInt());
        verify(healthRecords).findBeforeAnchor(eq(B), any(), any(), any());
        verify(completions).findTimelineViewsBefore(eq(B), any(), any());
        verify(checkins).findForPetBefore(eq(B), any(), anyInt());
        verify(tailsonality).findForPetBefore(eq(B), any(), anyInt());
        verify(content, never()).findGrowthMomentsBeforeAnchor(eq(OWNER), eq(A), any(), any(), any(), anyInt());
    }

    @Test
    void timelineWithoutPetIdUsesDefaultA() {
        service.getTimeline(OWNER, null, 20, ALL);

        verify(content).findGrowthMomentsBeforeAnchor(eq(OWNER), eq(A), any(), any(), any(), anyInt());
        verify(health).recentHealthEvents(eq(A), any(), any(), anyInt());
    }

    @Test
    void calendarDayAndStatsWithPetIdB() {
        LocalDate d = LocalDate.of(2026, 1, 5);
        service.getCalendarMonth(OWNER, B, 2026, 1, ALL);
        service.getDayDetail(OWNER, B, d, ALL);
        service.getStats(OWNER, B);

        verify(content).findGrowthMomentsInMonth(eq(OWNER), eq(B), any(), any());
        verify(health).healthEventsInRange(eq(B), any(), any());
        verify(checkins).findForPetInUtcRange(eq(B), any(), any());
        verify(content).findGrowthMomentsOnDate(OWNER, B, d);
        verify(health).healthEventsOnDay(B, d);
        verify(checkins).findForPetOnUtcDate(B, d);
        verify(tailsonality).findForPetOnUtcDate(B, d);
        verify(content).countGrowthMoments(OWNER, B);
        verify(health).countHealthEvents(B);
        verify(healthRecords).countByPetProfileId(B);
        verify(content, never()).countGrowthMoments(OWNER, A);
    }

    @Test
    void statsWithoutPetIdUsesDefaultA() {
        service.getStats(OWNER);
        verify(content).countGrowthMoments(OWNER, A);
        verify(health).countHealthEvents(A);
    }

}
