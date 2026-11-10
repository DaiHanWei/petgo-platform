package com.tailtopia.shared.logistics.biteship.dto;

/**
 * 一条快递报价（Biteship {@code pricing[]} 取用字段，v1.4.2 shop Story 1.1）。
 *
 * @param price 最终价（IDR，小数已按 CEILING 取整）
 */
public record BiteshipRate(
        String courierCode,
        String courierName,
        String courierServiceCode,
        String courierServiceName,
        String duration,
        String shipmentDurationRange,
        String shipmentDurationUnit,
        long price,
        boolean availableForInsurance) {
}
