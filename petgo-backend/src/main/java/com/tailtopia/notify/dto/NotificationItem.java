package com.tailtopia.notify.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tailtopia.notify.domain.Notification;
import java.time.Instant;

/**
 * 通知中心列表项（Story 6.6）。token 用于标记已读；{@code deepLinkType + targetRef} 供客户端算跳转 location。
 *
 * <p><b>targetRef 暴露说明</b>：id 寻址类通知（CONTENT_LIKED/CONTENT_COMMENTED→帖子 id、
 * VET_REPLY/CONSULT_CLOSED→会话 id）的目标页在客户端本就以数字 id 寻址（feed `/content/{id}`、
 * `/consult/conversation/{id}`），targetRef 即这些 id，未引入新的可枚举暴露；且仅下发给通知本人。
 * 固定目标类（生日/纪念日/里程碑）的 targetRef 为内部标识（如 "C-L1"），客户端不用于寻址。
 *
 * <h2>V1.3.4 follow · Story 3.3：互动条目「谁 + 做了什么」</h2>
 * 四种互动类型且有触发人行的条目，{@code title / body} 是服务端按接收方语言<b>现拼</b>的（与推送同一模板），并带：
 * <ul>
 *   <li>{@code actorName / actorUserId / actorToken}：最新可显示的触发人（昵称现查；数字 id 只用于跳现有主页路由，
 *       关注操作一律用 token，D-2）；</li>
 *   <li>{@code actorCount}：过滤后的触发人总数（含 actorName 那一位）；模板里的 N = actorCount − 1；</li>
 *   <li>{@code actorIFollow / actorFollowsMe}：<b>只对 USER_FOLLOWED</b> —— 我是否已关注该触发人 / 他是否关注我（T-41），
 *       供回关按钮按三态显示；其余类型为 null。</li>
 * </ul>
 * 其它条目（系统类、上线前无触发人的存量）原样返回，新字段为 null / 0（全局 NON_NULL：null 键不下发）。
 * <b>不暴露</b>通知 id、评论 id、合并键、variant。
 */
public record NotificationItem(
        String type,
        String title,
        String body,
        String deepLinkType,
        String deepLinkToken,
        String targetRef,
        boolean read,
        Instant createdAt,
        @JsonProperty("actorName") String actorName,
        @JsonProperty("actorCount") int actorCount,
        @JsonProperty("actorUserId") Long actorUserId,
        @JsonProperty("actorToken") String actorToken,
        @JsonProperty("actorIFollow") Boolean actorIFollow,
        @JsonProperty("actorFollowsMe") Boolean actorFollowsMe) {

    /** 原样返回（系统类 / 存量）：title / body 照搬库值，触发人字段为空。 */
    public static NotificationItem from(Notification n) {
        return new NotificationItem(n.getType().name(), n.getTitle(), n.getBody(),
                n.getDeepLinkType(), n.getDeepLinkToken(), n.getTargetRef(), n.isRead(), n.getCreatedAt(),
                null, 0, null, null, null, null);
    }

    /**
     * 现拼的互动条目（Story 3.3）：{@code title / body} 覆盖库值，其余字段照原样。
     *
     * @param iFollow / followsMe 只对 USER_FOLLOWED 传值，其余类型传 null
     */
    public static NotificationItem rendered(Notification n, String title, String body, long actorUserId,
            String actorName, String actorToken, int actorCount, Boolean iFollow, Boolean followsMe) {
        return new NotificationItem(n.getType().name(), title, body,
                n.getDeepLinkType(), n.getDeepLinkToken(), n.getTargetRef(), n.isRead(), n.getCreatedAt(),
                actorName, actorCount, actorUserId, actorToken, iFollow, followsMe);
    }
}
