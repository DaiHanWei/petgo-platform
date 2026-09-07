package com.tailtopia.notify.service;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.profile.domain.MilestoneLevel;
import com.tailtopia.profile.event.MilestoneCompletedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 里程碑达成通知订阅（Story 8.6，FR-42 / FR-34；V1.1.6 Story 6.1 补 S/M · FR-76 / AD-13）。
 *
 * <p>跨模块经领域事件（profile 不直调 notify）：
 * {@link MilestoneCompletedEvent} 中 **L 级** → 经 6.1 {@link NotificationService} 下发
 * {@code MILESTONE_NODE} 通知至通知中心（6.6 真数据）+ 已授权用户同时收系统推送；点击深链跳成长档案 Tab →
 * 里程碑列表页（FR-38，App 侧 deepLink 路由 MILESTONE_NODE→/profile/milestones）。
 *
 * <h2>两级分工（V1.1.6 Story 6.1 起）</h2>
 * <ul>
 *   <li><b>L 级</b>：通知中心 + 系统推送（<b>行为一字未改</b>）。</li>
 *   <li><b>S/M 级</b>：<b>只写通知中心、不发系统推送</b> —— S/M 数量比 L 多得多，推送会变成打扰；
 *       但由**别人的互动**触发的那些（第一次被评论 / 第一次收到点赞），本人当时不在现场，
 *       不留痕就等于永远不知道。</li>
 * </ul>
 *
 * <p>✅ 两条"看起来要专门处理"的 AC 其实是**结构上白送的**，这里不写额外逻辑：
 * <ul>
 *   <li><b>一次解锁多个 → 写 N 条</b>：完成事件本来就是<b>每完成一条发一个</b>。</li>
 *   <li><b>存量不回填</b>：只对事件反应、不扫历史；且完成落库是幂等的，已完成的不会再发事件。</li>
 * </ul>
 * ⚠️ 但两者都有测试钉着 —— 哪天有人为了别的需求把事件改成"批量发一条"，这两条会同时静默失效。
 *
 * <p>护栏：逐条不合并、不引 MQ。文案印尼语（市场主语言）——
 * 不嵌 catalog 的 {@code titleZh}（中文参考标题，仅内部用；App 按 code 客户端本地化）；具名印尼语
 * 推送需后端补 {@code titleId}，列为后续增强。
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
        if (event.level() == MilestoneLevel.L) {
            notificationService.send(event.ownerId(), NotificationType.MILESTONE_NODE,
                    "Tonggak penting tercapai 🎉",
                    "Kamu baru saja membuka tonggak baru — lihat sekarang!",
                    NotificationType.MILESTONE_NODE.name(), event.code());
            return;
        }
        // S/M（V1.1.6 Story 6.1）：只写通知中心、不推送。
        //
        // 🔴 targetRef 传**里程碑编码**：通知中心显示的"是哪一条里程碑"由 App 拿这个编码
        // 去查它自己那份双语标题表 —— 后端不下发展示文案（杜绝中文泄漏到印尼语界面），
        // 所以下面这两段文案只是留痕，不会出现在任何界面上。
        //
        // 深链类型沿用 L 级那个：AC 要求点击落点与 L 级**一致、不做差异化**（里程碑列表页），
        // 复用同一个映射，客户端路由一行都不必改。
        notificationService.sendWithoutPush(event.ownerId(), NotificationType.MILESTONE_SM_NODE,
                "Tonggak tercapai", "Tonggak baru terbuka",
                NotificationType.MILESTONE_NODE.name(), event.code());
    }
}
