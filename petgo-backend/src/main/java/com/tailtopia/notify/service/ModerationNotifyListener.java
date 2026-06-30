package com.tailtopia.notify.service;

import com.tailtopia.moderation.event.ReportResolvedEvent;
import com.tailtopia.notify.domain.NotificationType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 举报处理闭环推送（Story 4.1，AB-3A）。消费 {@link ReportResolvedEvent} → 向**举报人**发统一模糊通知。
 *
 * <p>护栏（模糊）：文案对下架/驳回**完全一致**，<b>不透露</b>处置结果 / 被举报内容 / 作者；无申诉入口、无查询；
 * deepLink 不导向内容（举报人非作者）。跨模块经事件（不直访 moderation/content repository）。
 */
@Component
public class ModerationNotifyListener {

    private final NotificationService notificationService;

    public ModerationNotifyListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener
    public void onReportResolved(ReportResolvedEvent event) {
        notificationService.send(event.reporterId(), NotificationType.REPORT_REVIEWED,
                "举报已处理", "感谢你的举报，我们已完成审核。",
                NotificationType.REPORT_REVIEWED.name(), null);
    }
}
