package com.tailtopia.notify.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.MilestoneLevel;
import com.tailtopia.profile.event.MilestoneCompletedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * L0：里程碑达成的两级分工（Story 8.6 建立 L 级；V1.1.6 Story 6.1 补 S/M）。
 *
 * <p>这组测试守的是「哪一级走哪条路」——走错的后果不对称：
 * S/M 误走推送 = 用户被一堆小成就轰炸；L 级误走静默 = 重大节点没人知道。
 */
class MilestoneNotifyListenerTest {

    private final NotificationService notificationService = Mockito.mock(NotificationService.class);
    private final MilestoneNotifyListener listener = new MilestoneNotifyListener(notificationService);

    private static MilestoneCompletedEvent event(String code, MilestoneLevel level) {
        return new MilestoneCompletedEvent(7L, code, level, "标题",
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    /** L 级维持现状：通知中心 + 系统推送。**本 story 一字未改**，这条是它的回归。 */
    @Test
    void lLevelStillSendsPush() {
        listener.onMilestoneCompleted(event("C-L1", MilestoneLevel.L));

        verify(notificationService).send(eq(7L), eq(NotificationType.MILESTONE_NODE),
                any(), any(), eq(NotificationType.MILESTONE_NODE.name()), eq("C-L1"));
        verify(notificationService, never())
                .sendWithoutPush(anyLong(), any(), any(), any(), any(), any());
    }

    /**
     * 🛡 S/M 级：**写通知中心、不发系统推送**（AD-13 Rule 1）。
     *
     * <p>改版前这里是「直接 return、什么都不做」—— 由别人的互动触发的 S/M
     * （第一次被评论 / 第一次收到点赞）本人当时不在现场，因此完全不会被告知。
     */
    @Test
    void sAndMLevelWriteToCenterButNeverPush() {
        listener.onMilestoneCompleted(event("C-S1", MilestoneLevel.S));
        listener.onMilestoneCompleted(event("C-M8", MilestoneLevel.M));

        verify(notificationService, never()).send(anyLong(), any(), any(), any(), any(), any());
        verify(notificationService).sendWithoutPush(eq(7L),
                eq(NotificationType.MILESTONE_SM_NODE), any(), any(), any(), eq("C-S1"));
        verify(notificationService).sendWithoutPush(eq(7L),
                eq(NotificationType.MILESTONE_SM_NODE), any(), any(), any(), eq("C-M8"));
    }

    /**
     * 🔴 **每条都带自己的编码**（AC4）。
     *
     * <p>通知中心显示"是哪一条里程碑"全靠这个编码 —— App 拿它查自己那份双语标题表。
     * 若这里传了别的东西（比如统一传个常量），界面上就会退化成"你完成了一个里程碑"，
     * 正是 AC 明令禁止的那种看不出发生了什么的记录。
     */
    @Test
    void eachNotificationCarriesItsOwnMilestoneCode() {
        listener.onMilestoneCompleted(event("C-S14", MilestoneLevel.S));
        listener.onMilestoneCompleted(event("C-S15", MilestoneLevel.S));

        verify(notificationService).sendWithoutPush(anyLong(), any(), any(), any(), any(),
                eq("C-S14"));
        verify(notificationService).sendWithoutPush(anyLong(), any(), any(), any(), any(),
                eq("C-S15"));
    }

    /** 🛡 点击落点与 L 级**一致、不做差异化** —— 所以深链类型沿用 L 级那个。 */
    @Test
    void smSharesTheSameDeepLinkTargetAsL() {
        listener.onMilestoneCompleted(event("C-S1", MilestoneLevel.S));

        verify(notificationService).sendWithoutPush(anyLong(), any(), any(), any(),
                eq(NotificationType.MILESTONE_NODE.name()), any());
    }
}
