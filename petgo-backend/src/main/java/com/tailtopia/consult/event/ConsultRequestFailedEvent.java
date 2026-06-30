package com.tailtopia.consult.event;

import java.time.Instant;

/**
 * 问诊请求未成功事件（Story 2.9，AB-2G）。请求**从未建立会话**即失败时由 consult 发布，admin 订阅落
 * {@code failed_consult_requests}（独立于 Epic 5「已建立会话」的异常工单）。
 *
 * @param reason         取消原因 UPPER_SNAKE：USER_CANCEL / TIMEOUT / SYSTEM_FAILURE
 * @param onlineVetCount 失败时刻在线兽医数（发布点取，非落库时）
 */
public record ConsultRequestFailedEvent(String reason, long userId, long sessionId,
        Instant submittedAt, int onlineVetCount) {
}
