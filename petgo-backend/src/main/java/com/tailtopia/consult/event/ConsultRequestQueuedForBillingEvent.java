package com.tailtopia.consult.event;

/**
 * 计费流新问诊请求入队事件（Story 3.2，FR-22E）。用户免费发起 → {@code consult_requests}(QUEUEING) 落库后发布，
 * notify 订阅 → 读 Redis 在线兽医集合 → 向<b>在线</b>兽医推「有新的问诊请求」（离线不推）。
 *
 * <p>与 V1.0 {@code ConsultRequestQueuedEvent}（携 sessionId，consult_sessions 免费流）<b>区分</b>：本事件携
 * {@code requestId}（consult_requests 计费流），避免两流事件混淆。过去式命名。
 */
public record ConsultRequestQueuedForBillingEvent(long requestId) {
}
