package com.tailtopia.notify.service;

import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 离线推送投递（Story 6.1）。{@code @Async} 经 {@code shared/im} 离线通道下发，
 * <b>不引入 MQ / 独立 TPNS</b>。投递失败仅记日志（不阻塞主流程，不外泄 PII/token 正文）。
 */
@Component
public class NotificationPusher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPusher.class);

    private final TencentImClient imClient;

    public NotificationPusher(TencentImClient imClient) {
        this.imClient = imClient;
    }

    /** 推送给用户（IM 账号 u_{userId}）。 */
    @Async
    public void pushToUser(long userId, String title, String body, String deepLinkType, String deepLinkToken) {
        deliver(ImAccountMapper.userImId(userId), title, body, deepLinkType, deepLinkToken);
    }

    /** 推送给兽医（IM 账号 v_{vetId}，Story 6.2 新请求推送在线兽医）。 */
    @Async
    public void pushToVet(long vetId, String title, String body, String deepLinkType, String deepLinkToken) {
        deliver(ImAccountMapper.vetImId(vetId), title, body, deepLinkType, deepLinkToken);
    }

    private void deliver(String imUserId, String title, String body, String deepLinkType, String deepLinkToken) {
        try {
            imClient.pushOffline(imUserId, title, body, deepLinkType, deepLinkToken);
        } catch (RuntimeException e) {
            log.warn("离线推送投递失败 type={} cause={}", deepLinkType, e.getClass().getSimpleName());
        }
    }
}
