package com.tailtopia.auth.dto;

/**
 * 用户标签展示投影（V1.1.6 Story 5.1 · FR-74）。
 *
 * <p>随 {@link AuthorView} 一起下发，四处展示位共用同一份形状。
 *
 * @param code        稳定标识
 * @param name        标签名（tooltip 标题）
 * @param icon        图标（emoji 或图片地址）
 * @param description 一句说明（tooltip 正文）
 * @param badgeColor  徽章圆底色值，形如 {@code #F6A609}（2026-08-28，UI 稿 `.utag-icon` 按标签分色）。
 *                    🔴 下发的是**色值**而不是枚举名：客户端因此不必认识调色板，
 *                    将来加一档颜色不需要发版。客户端解析失败时自行回落金色。
 */
public record UserTagView(String code, String name, String icon, String description,
        String badgeColor) {
}
