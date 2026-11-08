package com.tailtopia.shop.logistics;

/**
 * 询价用的一项包裹（v1.4.2 shop Story 1.1）：重量克、尺寸厘米；{@code value} 为单价（整数 IDR）。
 *
 * <p>🔒 {@code toString} 不带商品名。
 */
public record PackageItem(String name, long value, int quantity, int weightG, int lengthCm,
        int widthCm, int heightCm) {

    @Override
    public String toString() {
        return "PackageItem{qty=" + quantity + ", weightG=" + weightG + ", " + lengthCm + "x"
                + widthCm + "x" + heightCm + "}";
    }
}
