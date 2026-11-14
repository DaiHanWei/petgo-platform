package com.tailtopia.admin.gift.dto;

/** 新建 / 编辑礼物分类的原始表单值（V1.5.0 gift-pack Story 1-2）。排序保持字符串，「不是整数」落成人话 422。 */
public record GiftCategoryForm(String nameEn, String nameId, String sortOrder) {
}
