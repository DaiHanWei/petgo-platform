package com.petgo.notify.dto;

import com.petgo.notify.domain.Notification;
import java.time.Instant;

/**
 * 通知中心列表项（Story 6.6）。<b>对外只暴露 token + deepLinkType，不返回顺序 id / target_ref 内部字段</b>。
 */
public record NotificationItem(
        String type,
        String title,
        String body,
        String deepLinkType,
        String deepLinkToken,
        boolean read,
        Instant createdAt) {

    public static NotificationItem from(Notification n) {
        return new NotificationItem(n.getType().name(), n.getTitle(), n.getBody(),
                n.getDeepLinkType(), n.getDeepLinkToken(), n.isRead(), n.getCreatedAt());
    }
}
