package com.tailtopia.admin.moderation.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 头像审核处置页行投影（story 8，§6.2）。数据来自 story 5 {@code AvatarModerationService.pendingQueue()}
 * （{@code MANUAL_PENDING} 记录）。处置 POST 到 story 8 端点 {@code /admin/avatar-review/{reviewId}/decide}
 * （story 8 回补，委托 {@code AvatarModerationService.decide}）。
 *
 * <p>{@code avatarUrl} 为<b>审核证据</b>（头像图片，可能含敏感图像）——仅授权运营可见（缩略图预览），受访问控制
 * 管控（§5.5，不入日志/审计）。{@code reviewId} 为内部审核记录 id（admin 内部处置句柄，不外露）。
 *
 * @param reviewId    头像审核记录 id（处置 POST 句柄）
 * @param subjectType USER_AVATAR / PET_AVATAR
 * @param avatarUrl   送审头像 URL（审核证据；仅授权运营缩略预览）
 * @param riskScore   异步图像风险分（降级为 null）
 * @param createdAt   送审时间
 * @param strikes     作者累计违规计数（§5.4，只读展示）
 */
public record AvatarReviewRow(long reviewId, String subjectType, String avatarUrl,
        BigDecimal riskScore, Instant createdAt, ViolationCounts strikes) {
}
