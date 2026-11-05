package com.tailtopia.shared.logistics.biteship.dto;

/**
 * 下运单请求（v1.4.2 shop Story 1.1 只定签名；字段由 Story 5.1 按 AC4.2 补全）。
 *
 * @param referenceId   本平台运单参考号（每次重试换新）
 * @param courierCompany 订单快照 {@code courier_code}
 * @param courierType    订单快照 {@code courier_service_code}
 * @param price          订单运费（IDR）
 */
public record BiteshipCreateOrderRequest(String referenceId, String courierCompany,
        String courierType, long price) {
}
