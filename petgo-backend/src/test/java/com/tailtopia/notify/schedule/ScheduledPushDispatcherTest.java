package com.tailtopia.notify.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.domain.ScheduledPushMark;
import com.tailtopia.notify.repository.ScheduledPushMarkRepository;
import com.tailtopia.notify.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：定时推送逐条投递（Story 6.7；V1.5.0 onboarding-recall Story 5.2 · AC2 / AC5）—— 纪念日改走 {@code sendRendered}
 * （透传 push 标志），生日 / 第一个生日仍走 {@code sendWithCopy}；两种都先写去重标记；已推跳过。
 */
class ScheduledPushDispatcherTest {

    private final ScheduledPushMarkRepository marks = mock(ScheduledPushMarkRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ScheduledPushDispatcher dispatcher = new ScheduledPushDispatcher(marks, notifications);

    @Test
    @DisplayName("纪念日 → sendRendered(copyKey=COMPANION_ANNIVERSARY, args=[宠物名, 天数], targetRef=petId, push)；写标记")
    void anniversaryUsesSendRendered() {
        dispatcher.dispatch(new PlannedPush(7, 10, NotificationType.COMPANION_ANNIVERSARY, "30", "Mochi", 30, false));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notifications).sendRendered(eq(10L), eq(NotificationType.COMPANION_ANNIVERSARY),
                eq("COMPANION_ANNIVERSARY"), args.capture(), anyString(), anyString(), eq("7"), eq(false));
        org.assertj.core.api.Assertions.assertThat(args.getValue()).containsExactly("Mochi", "30");
        verify(notifications, never()).sendWithCopy(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(),
                any(), any(), any());
        verify(marks).save(any(ScheduledPushMark.class));
    }

    @Test
    @DisplayName("生日 / 第一个生日 → 仍走 sendWithCopy（行为不变）")
    void birthdayStillUsesSendWithCopy() {
        dispatcher.dispatch(new PlannedPush(7, 10, NotificationType.PET_BIRTHDAY, "2026", "Mochi", 1));
        dispatcher.dispatch(new PlannedPush(7, 10, NotificationType.MILESTONE_NODE, "FIRST_BIRTHDAY", "Mochi", 1));

        verify(notifications).sendWithCopy(eq(10L), eq(NotificationType.PET_BIRTHDAY), eq("PET_BIRTHDAY"), any(),
                anyString(), anyString(), eq("7"));
        verify(notifications).sendWithCopy(eq(10L), eq(NotificationType.MILESTONE_NODE),
                eq("MILESTONE_NODE.FIRST_BIRTHDAY"), any(), anyString(), anyString(), eq("7"));
        verify(notifications, never()).sendRendered(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(),
                any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("已有去重标记 → 跳过")
    void alreadyMarkedSkips() {
        when(marks.existsByPetProfileIdAndPushKindAndNodeKey(7L, "COMPANION_ANNIVERSARY", "30")).thenReturn(true);
        dispatcher.dispatch(new PlannedPush(7, 10, NotificationType.COMPANION_ANNIVERSARY, "30", "Mochi", 30, true));
        verify(marks, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(notifications);
    }
}
