package com.tailtopia.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * 当前用户资料更新请求（PATCH /api/v1/me）。两字段均可选（部分更新）：
 * <ul>
 *   <li>{@code nickname}：≤20 字（Bean Validation 强制，超出 422）。</li>
 *   <li>{@code petStatus}：A|B|C（service 校验枚举，非法 422；首次设置同时置 onboarding 完成）。</li>
 *   <li>{@code phone}：手机号（V1.1.6 Story 7.1 · FR-70），选填、不验证、不用于登录。</li>
 * </ul>
 *
 * <h2>🔴 手机号：「没传」与「传了空」是两件事</h2>
 * 本请求是**部分更新**：字段为 {@code null} = 这次不动它。
 * 而 FR-70 要求「留空保存 = 撤回」，也就是**要能把它写成空**。
 *
 * <p>两者若都用 {@code null} 表达，就永远分不清「我没改手机号」和「我要删掉手机号」——
 * **撤回权直接落空，而且是静默的**（用户清空保存、提示说成功，实际没删）。
 *
 * <p>故约定：<b>不传 = 不动；传空串（或全是空白）= 清空</b>。
 *
 * <p>⚠️ 字段名必须是 {@code phone}：日志脱敏按字段名整串打码，改名即静默失去保护。
 */
public record UpdateMeRequest(@Size(max = 20) String nickname, String petStatus,
        @Size(max = 1024) String avatarUrl, @Size(max = 60) String signature,
        @Size(max = 32) String phone) {
}
