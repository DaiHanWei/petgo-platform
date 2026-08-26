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
 */
public record UserTagView(String code, String name, String icon, String description) {
}
