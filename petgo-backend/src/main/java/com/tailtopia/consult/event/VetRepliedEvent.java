package com.tailtopia.consult.event;

/**
 * 兽医已回复事件（Story 6.2，FR-22A）。兽医在会话中回复时发布，notify 订阅 → 推送用户「有新回复」。
 *
 * <p>过去式命名。V1 触发源：兽医客户端发完 IM 消息后 ping 后端（或腾讯 IM 回调，L2）。
 * 携 {@code recipientUserId}（收件用户）+ {@code sessionId}（深链定位经 notify 转 token）。
 */
public record VetRepliedEvent(long sessionId, long recipientUserId) {
}
