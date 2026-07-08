package com.tailtopia.notify.service;

import com.tailtopia.avatarmoderation.event.AvatarResetEvent;
import com.tailtopia.notify.domain.NotificationType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 头像违规重置推送订阅（内容审核 story 5，§5.5，D-CM6）。消费 {@link AvatarResetEvent} → 向本人发
 * {@code AVATAR_RESET} 通知（跨模块经事件，不直访 avatarmoderation service；与名称侧 {@code NameResetNotifyListener} 同构）。
 *
 * <p>护栏：<b>只发结构化通知</b>（type + targetRef），标题/正文留空——显示串由 App 按 type 本地化
 * （arb 文案归 cm-7，遵 [i18n 模型：App 按 code/type 本地化，不渲染后端串]）。{@code targetRef} 区分用户头像
 * （"USER_AVATAR"→我的页换头像入口）与宠物头像（cardToken→该宠物档案编辑页换头像）。{@code send} 本身
 * {@code REQUIRES_NEW}（防 AFTER_COMMIT 事务吞写，见 [notify AFTER_COMMIT 事务吞写] 教训）。
 * {@code AUTO_PASSED}/{@code STALE_DISCARDED}/运营判过等不发事件故不触达此处。
 */
@Component
public class AvatarResetNotifyListener {

    private final NotificationService notificationService;

    public AvatarResetNotifyListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener
    public void onAvatarReset(AvatarResetEvent event) {
        notificationService.send(event.recipientUserId(), NotificationType.AVATAR_RESET,
                null, null, NotificationType.AVATAR_RESET.name(), event.targetRef());
    }
}
