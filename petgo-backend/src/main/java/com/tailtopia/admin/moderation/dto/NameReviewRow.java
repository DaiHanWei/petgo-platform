package com.tailtopia.admin.moderation.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 名称审核处置页行投影（story 8，§6.2）。数据来自 story 4 {@code NameModerationService.pendingQueue()}
 * （{@code MANUAL_PENDING} 记录）。处置 POST 到 story 4 既有端点 {@code /admin/name-moderation/{recordId}/decide}。
 *
 * <p>{@code valuePreview} 为<b>审核证据</b>（名称原文，可能含 PII）——仅授权运营（{@code content.takedown}）可见，
 * 受访问控制管控（§5.5，不脱敏、不入日志/审计）。{@code recordId} 为内部审核记录 id（admin 内部处置句柄，
 * 与 manual-review 用 itemId 一致；不外露给 App）。
 *
 * @param recordId    名称审核记录 id（处置 POST 句柄）
 * @param targetType  NICKNAME / PET_NAME
 * @param valuePreview 送审名称原文（审核证据；已截断）
 * @param riskScore   异步风险分（降级为 null）
 * @param submittedAt 送审时间
 * @param strikes     作者累计违规计数（§5.4，只读展示）
 */
public record NameReviewRow(long recordId, String targetType, String valuePreview,
        BigDecimal riskScore, Instant submittedAt, ViolationCounts strikes) {
}
