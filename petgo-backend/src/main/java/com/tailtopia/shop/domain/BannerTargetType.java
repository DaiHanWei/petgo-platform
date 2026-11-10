package com.tailtopia.shop.domain;

/**
 * Toko banner 点击跳转类型（V1.4.1）。落库 varchar + UPPER_SNAKE，与 {@code ck_shop_banners_target_type} 一致。
 *
 * <p>v1.4.2 shop Story 6.1（D-16）加 {@link #COUPON}（领券）。
 * 🔴 {@code TOKO_HOME} <b>不是</b>本枚举的值：它只是新端点对 {@link #CATEGORY} 的下发值（品类筛选下线），不落库。
 * 🔴 只在末尾追加，不重排 / 不删除。
 */
public enum BannerTargetType {
    /** 不可点（默认，与 V1.4.0 及以前的纯展示一致）。 */
    NONE,
    /** 商品详情；target_value = 商品 public_token。 */
    PRODUCT,
    /** 切到 Toko 对应品类；target_value = {@link ProductCategory} 字面量。 */
    CATEGORY,
    /** 系统浏览器打开；target_value = https 链接。 */
    EXTERNAL,
    /** 领券（v1.4.2）；target_value = 券模板 public_token，只允许 CLAIM 类券。 */
    COUPON
}
