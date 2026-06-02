package com.petgo.notify.service;

import com.petgo.shared.im.ImAccountMapper;
import com.petgo.shared.im.TencentImClient;
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

    @Async
    public void push(long recipientUserId, String title, String body, String deepLinkType, String deepLinkToken) {
        try {
            imClient.pushOffline(ImAccountMapper.userImId(recipientUserId), title, body, deepLinkType, deepLinkToken);
        } catch (RuntimeException e) {
            log.warn("离线推送投递失败 recipient={} type={} cause={}",
                    recipientUserId, deepLinkType, e.getClass().getSimpleName());
        }
    }
}
