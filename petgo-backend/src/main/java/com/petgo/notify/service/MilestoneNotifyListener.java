package com.petgo.notify.service;

import com.petgo.notify.domain.NotificationType;
import com.petgo.profile.domain.MilestoneLevel;
import com.petgo.profile.event.MilestoneCompletedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * L 级里程碑达成推送订阅（Story 8.6，FR-42 / FR-34）。跨模块经领域事件（profile 不直调 notify）：
 * {@link MilestoneCompletedEvent} 中 **L 级** → 经 6.1 {@link NotificationService} 下发
 * {@code MILESTONE_NODE} 通知至通知中心（6.6 真数据）+ 已授权用户同时收系统推送；点击深链跳成长档案 Tab →
 * 里程碑列表页（FR-38，App 侧 deepLink 路由 MILESTONE_NODE→/profile/milestones）。
 *
 * <p>护栏：仅 L 级触达（S/M 不推，避免打扰）；逐条不合并、不引 MQ。文案中文常量（i18n 既有系统级缺口）。
 */
@Component
public class MilestoneNotifyListener {

    private final NotificationService notificationService;

    public MilestoneNotifyListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Async
    @TransactionalEventListener
    public void onMilestoneCompleted(MilestoneCompletedEvent event) {
        if (event.level() != MilestoneLevel.L) {
            return; // 仅 L 级达成推送。
        }
        notificationService.send(event.ownerId(), NotificationType.MILESTONE_NODE,
                "重大里程碑达成 🎉", "「" + event.title() + "」已解锁，快去看看吧",
                NotificationType.MILESTONE_NODE.name(), event.code());
    }
}
