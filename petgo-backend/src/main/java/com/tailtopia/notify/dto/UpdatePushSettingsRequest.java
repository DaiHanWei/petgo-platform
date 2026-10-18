package com.tailtopia.notify.dto;

import com.tailtopia.notify.preference.PushCategory;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * {@code PATCH /api/v1/me/push-settings} 请求体（V1.4.0 ④ Story 1.1 · AD-4）：部分更新，没出现的分类不动。
 *
 * <p>map key 是 {@link PushCategory} 枚举：未知 code（含小写）Jackson 反序列化失败 → 400（GlobalExceptionHandler）。
 * 值为 null / {@code ORDER_ACCOUNT:false} 的校验在 service（422）。
 */
public record UpdatePushSettingsRequest(@NotNull Map<PushCategory, Boolean> categories) {
}
