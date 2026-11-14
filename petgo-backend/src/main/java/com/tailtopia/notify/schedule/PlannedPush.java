package com.tailtopia.notify.schedule;

import com.tailtopia.notify.domain.NotificationType;

/**
 * 一条待投递的定时推送计划（Story 6.7）。由 {@link ScheduledPushPlanner} 纯逻辑产出，
 * 交 {@code ScheduledPushDispatcher} 经 6.1 {@code NotificationService} 投递 + 写去重标记。
 *
 * @param number 生日=岁数 / 纪念日=天数 / 里程碑=岁数（文案用）。
 * @param push   是否推送（V1.5.0 onboarding-recall Story 5.2 · FR-182：同主人同天多只纪念日只推最早那只，
 *               其余 {@code false} = 只写通知中心）。生日 / 第一个生日恒 {@code true}。
 */
public record PlannedPush(
        long petProfileId,
        long ownerId,
        NotificationType type,
        String nodeKey,
        String petName,
        int number,
        boolean push) {

    /** 6 参兼容构造器（V1.5.0 前的形态）：{@code push = true}。 */
    public PlannedPush(long petProfileId, long ownerId, NotificationType type, String nodeKey, String petName,
            int number) {
        this(petProfileId, ownerId, type, nodeKey, petName, number, true);
    }

    /** 去重键（与 scheduled_push_marks 唯一约束三元组一致）。 */
    public String markKey() {
        return petProfileId + "|" + type.name() + "|" + nodeKey;
    }
}
