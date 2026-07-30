package com.tailtopia.notify.service;

import com.tailtopia.consult.event.ConsultRequestQueuedEvent;
import com.tailtopia.consult.event.ConsultRequestQueuedForBillingEvent;
import com.tailtopia.consult.event.VetRepliedEvent;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.vet.service.VetPresenceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 问诊域推送订阅（Story 6.2）。跨模块经领域事件（<b>不直访 consult/vet repository</b>）：
 * <ul>
 *   <li>{@link VetRepliedEvent} → 推送用户「有新回复」（FR-22A，正在查看抑制由客户端在场态处理）。</li>
 *   <li>{@link ConsultRequestQueuedEvent} → 读 Redis 在线兽医集合，向<b>在线</b>兽医推送「有新请求」（FR-22E，离线不推）。</li>
 * </ul>
 * 文案 V1 服务端中文文案（客户端 i18n 收口可后续切 key）。AFTER_COMMIT 确保会话已落库。
 */
@Component
public class ConsultNotifyListener {

    private final NotificationService notificationService;
    private final VetPresenceService presence;

    public ConsultNotifyListener(NotificationService notificationService, VetPresenceService presence) {
        this.notificationService = notificationService;
        this.presence = presence;
    }

    @TransactionalEventListener
    public void onVetReplied(VetRepliedEvent event) {
        notificationService.send(event.recipientUserId(), NotificationType.VET_REPLY,
                "你的宠物问诊有新回复", "点击查看",
                NotificationType.VET_REPLY.name(), String.valueOf(event.sessionId()));
    }

    @TransactionalEventListener
    public void onConsultRequestQueued(ConsultRequestQueuedEvent event) {
        broadcastNewRequest();
    }

    /**
     * Story 3.2 计费流入队 → 广播在线兽医（FR-22E，离线不推）。与 V1.0 {@link ConsultRequestQueuedEvent} 同样广播，
     * 事件来源不同（consult_requests 计费流 vs consult_sessions 免费流），推送文案一致。
     */
    @TransactionalEventListener
    public void onConsultRequestQueuedForBilling(ConsultRequestQueuedForBillingEvent event) {
        broadcastNewRequest();
    }

    private void broadcastNewRequest() {
        for (Long vetId : presence.onlineVetIds()) {
            notificationService.sendToVet(vetId, NotificationType.NEW_CONSULT_REQUEST,
                    "有新的问诊请求", "点击查看", NotificationType.NEW_CONSULT_REQUEST.name());
        }
    }
}
