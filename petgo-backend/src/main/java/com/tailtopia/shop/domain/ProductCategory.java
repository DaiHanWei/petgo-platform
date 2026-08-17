package com.tailtopia.shop.domain;

/**
 * 商品品类（FR-94 ③）。Toko 首页区域③ 的四个固定分类入口。
 *
 * <p>落库 varchar + CHECK（{@code ck_shop_products_category}），UPPER_SNAKE。
 * 🔴 <b>只在末尾追加，不重排 / 不删除 / 不改既有值拼写</b>——重排不报错但会静默改变全部历史行的语义。
 */
public enum ProductCategory {
    /** 粮 */
    MAKANAN,
    /** 驱虫保健 */
    OBAT_VITAMIN,
    /** 零食 */
    CAMILAN,
    /** 洗护 */
    PERAWATAN
}
