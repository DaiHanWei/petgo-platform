package com.tailtopia.consult.event;

/**
 * 新问诊请求入队事件（Story 6.2，FR-22E）。用户发起咨询进 WAITING 待接单队列时发布，
 * notify 订阅 → 读 Redis 在线兽医集合 → 向**在线**兽医推送「有新的问诊请求」（离线不推）。
 *
 * <p>过去式命名。携 {@code sessionId}（深链定位 → 工作台待接单）。
 */
public record ConsultRequestQueuedEvent(long sessionId) {
}
