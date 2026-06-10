package com.tailtopia.notify.schedule;

import com.tailtopia.notify.domain.NotificationType;

/**
 * 一条待投递的定时推送计划（Story 6.7）。由 {@link ScheduledPushPlanner} 纯逻辑产出，
 * 交 {@code ScheduledPushDispatcher} 经 6.1 {@code NotificationService} 投递 + 写去重标记。
 *
 * @param number 生日=岁数 / 纪念日=天数 / 里程碑=岁数（文案用）。
 */
public record PlannedPush(
        long petProfileId,
        long ownerId,
        NotificationType type,
        String nodeKey,
        String petName,
        int number) {

    /** 去重键（与 scheduled_push_marks 唯一约束三元组一致）。 */
    public String markKey() {
        return petProfileId + "|" + type.name() + "|" + nodeKey;
    }
}
