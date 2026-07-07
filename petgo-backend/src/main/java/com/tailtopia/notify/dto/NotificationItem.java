package com.tailtopia.notify.dto;

import com.tailtopia.notify.domain.Notification;
import java.time.Instant;

/**
 * 通知中心列表项（Story 6.6）。token 用于标记已读；{@code deepLinkType + targetRef} 供客户端算跳转 location。
 *
 * <p><b>targetRef 暴露说明</b>：id 寻址类通知（CONTENT_LIKED/CONTENT_COMMENTED→帖子 id、
 * VET_REPLY/CONSULT_CLOSED→会话 id）的目标页在客户端本就以数字 id 寻址（feed `/content/{id}`、
 * `/consult/conversation/{id}`），targetRef 即这些 id，未引入新的可枚举暴露；且仅下发给通知本人。
 * 固定目标类（生日/纪念日/里程碑）的 targetRef 为内部标识（如 "C-L1"），客户端不用于寻址。
 */
public record NotificationItem(
        String type,
        String title,
        String body,
        String deepLinkType,
        String deepLinkToken,
        String targetRef,
        boolean read,
        Instant createdAt) {

    public static NotificationItem from(Notification n) {
        return new NotificationItem(n.getType().name(), n.getTitle(), n.getBody(),
                n.getDeepLinkType(), n.getDeepLinkToken(), n.getTargetRef(), n.isRead(), n.getCreatedAt());
    }
}
