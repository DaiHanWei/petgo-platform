package com.tailtopia.moderation.dto;

import com.tailtopia.moderation.domain.AccountReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 账号举报请求（Story 2.1）。
 *
 * <p>⚠️ {@code detail} 是<b>用户自由文本</b>：只在 {@code reason=OTHER} 时要求填写并保存，
 * 其余四类一律不保存。<b>该文本禁止进入任何日志</b>（日志禁 PII 红线）——
 * 「其他」里用户可能写进第三方姓名、聊天记录、联系方式。
 *
 * @param targetUserId 被举报的账号
 * @param reason       账号维度五类（≠ 内容维度的 {@code ReportReason}）
 * @param detail       仅 OTHER 必填，≤200 字。空/长度的具体校验在 service，
 *                     好让「选了其他却没填」返回一句人话而不是字段级错误集合
 */
public record AccountReportRequest(
        @NotNull(message = "请指定要举报的账号") Long targetUserId,
        @NotNull(message = "请选择举报类型") AccountReportReason reason,
        @Size(max = 200, message = "补充说明不能超过 200 字") String detail) {
}
