package com.tailtopia.consult.dto;

import java.time.Instant;

/**
 * 兽医未评问诊条目（Story 6.2，AB-6B）。仅运营可见，仅元数据（会话标识 + 终态时间 + 原因），
 * 不含 IM 正文/AI 上下文/媒体（NFR5）。
 *
 * @param terminalAt 终态时间（{@code ConsultSession.terminalAt()}）
 */
public record VetUnratedConsult(long sessionId, Instant terminalAt, UnratedReason reason) {
}
