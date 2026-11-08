package com.tailtopia.shared.logistics.biteship.dto;

import java.util.List;

/**
 * 询价请求（v1.4.2 shop Story 1.1）。起止点只用 5 位邮编（首位非 0）。
 *
 * <p>🔒 {@code toString} 不含完整邮编与商品名（record 默认 toString 会带出，故重写）。
 */
public record BiteshipRatesRequest(String originPostalCode, String destPostalCode, List<Item> items) {

    public BiteshipRatesRequest {
        items = List.copyOf(items);
    }

    /** 一项包裹：重量克、尺寸厘米；{@code value} 为单价（整数 IDR）。 */
    public record Item(String name, long value, int quantity, int weightG, int lengthCm,
            int widthCm, int heightCm) {

        @Override
        public String toString() {
            return "Item{qty=" + quantity + ", weightG=" + weightG + ", " + lengthCm + "x" + widthCm
                    + "x" + heightCm + "}";
        }
    }

    @Override
    public String toString() {
        return "BiteshipRatesRequest{dest=" + prefix(destPostalCode) + "***, items=" + items + "}";
    }

    private static String prefix(String pc) {
        return pc == null || pc.length() < 2 ? "" : pc.substring(0, 2);
    }
}
