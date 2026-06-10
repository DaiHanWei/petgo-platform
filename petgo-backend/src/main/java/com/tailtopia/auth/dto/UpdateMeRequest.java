package com.tailtopia.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * 当前用户资料更新请求（PATCH /api/v1/me）。两字段均可选（部分更新）：
 * <ul>
 *   <li>{@code nickname}：≤20 字（Bean Validation 强制，超出 422）。</li>
 *   <li>{@code petStatus}：A|B|C（service 校验枚举，非法 422；首次设置同时置 onboarding 完成）。</li>
 * </ul>
 */
public record UpdateMeRequest(@Size(max = 20) String nickname, String petStatus,
        @Size(max = 1024) String avatarUrl) {
}
