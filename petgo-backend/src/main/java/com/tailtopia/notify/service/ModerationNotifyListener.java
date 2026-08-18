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
 *
 * <p><b>⚠️ 2026-08-16（V1.1.4 Story 3.4）文案有意变更</b>：
 * 旧「感谢你的举报，我们已完成审核。」→ 新「你的举报已处理，感谢你帮助维护社区环境」。
 * <b>这不是修 bug，是产品定稿</b>（PRD §6 第 2 条）。它是内容举报与账号举报<b>共用的同一条通知</b>，
 * 所以<b>已经上线的内容举报回告也跟着变了 —— 有意为之</b>。
 * 日后拿线上文案与更早的文档比对，别以为是谁改错了又改回去。
 *
 * <p>⚠️ 同一句话落在<b>三处</b>，改一处就会前后不一致：
 * ① 这里（落库的 title/body）· ② {@code messages_*.properties} 的 {@code notify.REPORT_REVIEWED.*}
 * （离线推送按收件人语言渲染）· ③ <b>App 的 ARB {@code notifyBodyReportReviewed}</b>
 * （站内通知中心按 type 自行本地化，<b>这条才是用户真正看到的那句</b>）。
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
                "举报已处理", "你的举报已处理，感谢你帮助维护社区环境",
                NotificationType.REPORT_REVIEWED.name(), null);
    }
}
