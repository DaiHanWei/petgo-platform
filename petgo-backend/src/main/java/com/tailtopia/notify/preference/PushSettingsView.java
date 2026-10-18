package com.tailtopia.notify.preference;

import java.util.List;

/**
 * 推送分类设置（V1.4.0 ④ Story 1.1 · AD-4）：固定 9 项、按 {@link PushCategory} 声明顺序。
 * 直接作为 {@code GET / PATCH /api/v1/me/push-settings} 的响应体（枚举按 name 序列化）；不含分组 / 名称 / 说明（归 App）。
 */
public record PushSettingsView(List<Item> categories) {

    public record Item(PushCategory code, boolean enabled, boolean lockable) {
    }
}
