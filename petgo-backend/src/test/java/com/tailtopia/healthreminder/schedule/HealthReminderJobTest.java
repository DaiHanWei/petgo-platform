package com.tailtopia.healthreminder.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.service.HealthReminderSettingsDao;
import com.tailtopia.healthreminder.service.LaunchService;
import com.tailtopia.healthreminder.service.PregnancyResumeHandler;
import com.tailtopia.profile.service.PregnancyAutoClearService;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** L0（V1.5.0 Story 2.3 · AC1）：日跑顺序、开关关闭、出队让路与投递后记发送日。 */
class HealthReminderJobTest {

    private static final LocalDate D = LocalDate.parse("2027-03-01");

    private final HealthReminderSettingsDao settings = mock(HealthReminderSettingsDao.class);
    private final PregnancyAutoClearService autoClear = mock(PregnancyAutoClearService.class);
    private final PregnancyResumeHandler resume = mock(PregnancyResumeHandler.class);
    private final LaunchService launch = mock(LaunchService.class);
    private final DailyRunStore store = mock(DailyRunStore.class);
    private final OccupancyCalendar occupancy = mock(OccupancyCalendar.class);
    private final PetDayProcessor processor = mock(PetDayProcessor.class);
    private final QueueDayProcessor queue = mock(QueueDayProcessor.class);
    private final HealthReminderJob job = new HealthReminderJob(settings, autoClear, resume, launch, store, occupancy,
            processor, queue);

    private void enabled(boolean queuePaused) {
        when(settings.read()).thenReturn(new HealthReminderSettingsDao.Settings(true, null, D, queuePaused, null));
    }

    @Test
    void disabledOnlyAutoClearsPregnancy() {
        when(settings.read()).thenReturn(new HealthReminderSettingsDao.Settings(false, null, null, false, null));
        when(autoClear.clearExpiredPets(D)).thenReturn(List.of());
        assertThat(job.runFor(D).enabled()).isFalse();
        verify(autoClear).clearExpiredPets(D); // 档案规则不受上线开关影响
        verifyNoInteractions(launch, store, processor, queue);
    }

    @Test
    void runsStepsInOrderAndQueueWaitsWhenUserGotAPush() {
        enabled(false);
        when(autoClear.clearExpiredPets(D)).thenReturn(List.of(5L));
        when(store.expireMissed(D)).thenReturn(new EnumMap<>(CancelReason.class));
        when(store.duePets(D)).thenReturn(new DailyRunStore.DuePets(List.of(5L), Map.of(5L, 7L),
                new EnumMap<>(CancelReason.class)));
        when(store.usersWithQueue()).thenReturn(List.of(7L, 8L));
        when(store.petsOfOwners(any())).thenReturn(Map.of(5L, 7L, 6L, 8L));
        when(store.usersNotifiedOn(D)).thenReturn(Set.of());
        OccupancyCalendar.Calendar cal = new OccupancyCalendar.Calendar(D, Map.of(), Map.of(), Map.of(5L, 7L, 6L, 8L));
        when(occupancy.load(any(), eq(D))).thenReturn(cal);
        when(processor.process(eq(5L), eq(D), any())).thenReturn(new PetDayProcessor.PetDayResult(1,
                new EnumMap<>(CancelReason.class)));
        when(queue.process(eq(8L), eq(D), anyBoolean(), anyBoolean())).thenReturn(true);

        HealthReminderJob.RunResult r = job.runFor(D);

        assertThat(r.groupsSent()).isEqualTo(1);
        assertThat(r.queueSent()).isEqualTo(1);
        InOrder order = inOrder(autoClear, resume, launch, store, occupancy, processor, queue);
        order.verify(autoClear).clearExpiredPets(D);
        order.verify(resume).onPregnancyCleared(eq(5L), any());
        order.verify(launch).onRun(D);
        order.verify(store).expireMissed(D);
        order.verify(store).duePets(D);
        order.verify(occupancy).load(any(), eq(D));
        order.verify(processor).process(eq(5L), eq(D), any());
        // 用户 7 今天已收到健康通知 → 排队让路（busy=true）；用户 8 照常
        order.verify(queue).process(7L, D, true, false);
        order.verify(queue).process(8L, D, false, false);
    }

    @Test
    void singlePetFailureDoesNotStopRun() {
        enabled(true);
        when(autoClear.clearExpiredPets(D)).thenReturn(List.of());
        when(store.expireMissed(D)).thenReturn(new EnumMap<>(CancelReason.class));
        when(store.duePets(D)).thenReturn(new DailyRunStore.DuePets(List.of(1L, 2L), Map.of(1L, 7L, 2L, 7L),
                new EnumMap<>(CancelReason.class)));
        when(store.usersWithQueue()).thenReturn(List.of());
        when(store.petsOfOwners(any())).thenReturn(Map.of());
        when(occupancy.load(any(), eq(D))).thenReturn(new OccupancyCalendar.Calendar(D, Map.of(), Map.of(),
                Map.of()));
        when(processor.process(eq(1L), eq(D), any())).thenThrow(new IllegalStateException("boom"));
        when(processor.process(eq(2L), eq(D), any())).thenReturn(new PetDayProcessor.PetDayResult(1,
                new EnumMap<>(CancelReason.class)));
        assertThat(job.runFor(D).groupsSent()).isEqualTo(1);
        verify(queue, never()).process(anyLong(), any(), anyBoolean(), anyBoolean());
    }
}
