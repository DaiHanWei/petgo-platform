package com.tailtopia.admin.gift.dto;

/**
 * 新建 / 编辑礼物的原始表单值（V1.4.0 gift Story 3.1 · AC4.2）。
 *
 * <p>数字字段保持字符串：「不是整数」要落成人话 422（{@code admin.err.gift.*}），而不是参数绑定失败的 400。
 */
public record GiftCatalogForm(String nameEn, String nameId, String price, boolean forCat, boolean forDog,
        boolean forOther, String kind, String minLevel, String sortOrder) {
}
