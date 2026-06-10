package com.petgo.notify.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.petgo.notify.domain.NotificationType;
import com.petgo.profile.domain.MilestoneLevel;
import com.petgo.profile.event.MilestoneCompletedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（Story 8.6）：仅 L 级里程碑达成 → MILESTONE_NODE 推送；S/M 不推。 */
class MilestoneNotifyListenerTest {

    private final NotificationService notificationService = Mockito.mock(NotificationService.class);
    private final MilestoneNotifyListener listener = new MilestoneNotifyListener(notificationService);

    @Test
    void lLevelSendsMilestoneNodePush() {
        listener.onMilestoneCompleted(
                new MilestoneCompletedEvent(7L, "C-L1", MilestoneLevel.L, "第一个生日"));
        verify(notificationService).send(eq(7L), eq(NotificationType.MILESTONE_NODE),
                any(), any(), eq(NotificationType.MILESTONE_NODE.name()), eq("C-L1"));
    }

    @Test
    void sAndMLevelDoNotPush() {
        listener.onMilestoneCompleted(
                new MilestoneCompletedEvent(7L, "C-S1", MilestoneLevel.S, "档案创建完成"));
        listener.onMilestoneCompleted(
                new MilestoneCompletedEvent(7L, "C-M8", MilestoneLevel.M, "陪伴满 30 天"));
        verify(notificationService, never()).send(anyLong(), any(), any(), any(), any(), any());
    }
}
