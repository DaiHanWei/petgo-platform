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
 */
public record ContentTagView(String code, String name, String icon, String description) {
}
