package com.petgo.consult.event;

/**
 * 兽医已接单事件（Story 5.3 定义占位，Story 5.5 接单时发布，Epic 6/6.2 notify 订阅推送「兽医已接受」）。
 *
 * <p>过去式命名（已发生的事实）。本故事仅定义 record，<b>不实现推送</b>（推送在 Epic 6）。
 * 用于「转 AI 后原 WAITING 请求保留，兽医后续接单仍能通知用户」的链路占位。
 */
public record ConsultAcceptedEvent(long sessionId, long userId, long vetId) {
}
