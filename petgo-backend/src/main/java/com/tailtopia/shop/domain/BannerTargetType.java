package com.tailtopia.shop.domain;

/**
 * Toko banner 点击跳转类型（V1.4.1）。落库 varchar + UPPER_SNAKE，与 {@code ck_shop_banners_target_type} 一致。
 *
 * <p>「领券」没有对应功能，本版不加 —— 加一个永远配不出来的枚举值，只会让 App 侧写出走不到的分支。
 */
public enum BannerTargetType {
    /** 不可点（默认，与 V1.4.0 及以前的纯展示一致）。 */
    NONE,
    /** 商品详情；target_value = 商品 public_token。 */
    PRODUCT,
    /** 切到 Toko 对应品类；target_value = {@link ProductCategory} 字面量。 */
    CATEGORY,
    /** 系统浏览器打开；target_value = https 链接。 */
    EXTERNAL
}
