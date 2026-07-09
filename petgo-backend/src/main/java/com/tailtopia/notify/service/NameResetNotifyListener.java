package com.tailtopia.notify.service;

import com.tailtopia.namemoderation.event.NameResetEvent;
import com.tailtopia.notify.domain.NotificationType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 名称违规重置推送订阅（内容审核 story 4，§5.6，D-CM6）。消费 {@link NameResetEvent} → 向本人发
 * {@code NAME_RESET} 通知（跨模块经事件，不直访 namemoderation service）。
 *
 * <p>护栏：<b>只发结构化通知</b>（type + targetRef），标题/正文留空——显示串由 App 按 type 本地化
 * （arb 文案归 cm-7，遵 [i18n 模型：App 按 code/type 本地化，不渲染后端串]）。深链 token 由
 * {@link NotificationService} 生成；{@code targetRef} 区分昵称（"NICKNAME"→设置昵称页）与宠物名（cardToken→改名页）。
 * {@code AUTO_PASSED}/{@code RESOLVED_PASS} 等正向结果不发事件故不触达此处。
 */
@Component
public class NameResetNotifyListener {

    private final NotificationService notificationService;

    public NameResetNotifyListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener
    public void onNameReset(NameResetEvent event) {
        notificationService.send(event.recipientUserId(), NotificationType.NAME_RESET,
                null, null, NotificationType.NAME_RESET.name(), event.targetRef());
    }
}
