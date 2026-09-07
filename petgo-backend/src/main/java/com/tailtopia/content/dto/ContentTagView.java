package com.tailtopia.content.dto;

/**
 * 内容装饰标签展示投影（V1.1.6 Story 5.2 · FR-75）。
 *
 * <p>随内容条目一起下发，三处展示位共用同一份形状。
 *
 * @param code        稳定标识
 * @param name        标签名（tooltip 标题）
 * @param icon        图标
 * @param description 一句说明（tooltip 正文）
 * @param badgeStart  胶囊渐变起点色值，形如 {@code #F6A609}（2026-08-28，底色改为可配）
 * @param badgeEnd    胶囊渐变终点色值
 *
 * <p>🔴 下发的是**色值**而不是枚举名：客户端因此不必认识调色板，
 * 将来加一档颜色不需要发版。解析不出来时客户端自行回落 UI 稿原始的橙→红。
 */
public record ContentTagView(String code, String name, String icon, String description,
        String badgeStart, String badgeEnd) {
}
