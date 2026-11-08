package com.tailtopia.shop.logistics;

/**
 * 一条规范化后的快递报价（v1.4.2 shop Story 1.1），亦是 {@code shipping_quotes.response} 的元素。
 *
 * @param courierKey {@code courierCode + ":" + courierServiceCode}，如 {@code jne:reg}
 *                   （冒号分隔：服务码可能含下划线）。埋点 {@code courier} 字段另拼，不复用本字段。
 * @param price      IDR 最终价
 */
public record CourierRate(String courierKey,
        String courierCode, String courierServiceCode,
        String courierName, String courierServiceName,
        String durationText, String durationRange, String durationUnit,
        long price, boolean insuranceSupported) {

    public static String keyOf(String courierCode, String courierServiceCode) {
        return courierCode + ":" + courierServiceCode;
    }
}
