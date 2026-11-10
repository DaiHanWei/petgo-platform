package com.tailtopia.shop.support;

import com.tailtopia.shop.domain.ShopSku;

/**
 * 测试造数：给 SKU 补齐包裹参数使其「可售」（v1.4.2 shop Story 1.1，D-10）。
 *
 * <p>SQL 造数对应四列：{@code package_weight_g=1000, package_length_cm=20, package_width_cm=15,
 * package_height_cm=10}。
 */
public final class ShopSkuFixtures {

    public static final int WEIGHT_G = 1000;
    public static final int LENGTH_CM = 20;
    public static final int WIDTH_CM = 15;
    public static final int HEIGHT_CM = 10;

    private ShopSkuFixtures() {
    }

    public static ShopSku sellable(ShopSku sku) {
        sku.applyPackage(WEIGHT_G, LENGTH_CM, WIDTH_CM, HEIGHT_CM);
        return sku;
    }
}
