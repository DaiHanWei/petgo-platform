package com.tailtopia.shared.pay;

/**
 * 发起收款请求（Story 1.1）。{@code orderId} = 意图对外 {@code public_token}（不可枚举），
 * 网关据此回填 {@code order_id}，回调据此定位意图。金额 {@code amount} 为最小币种单位整型（IDR 无小数）。
 *
 * @param orderId  对外订单号（= payment_intents.public_token）
 * @param amount   金额（最小币种单位整型）
 * @param currency 币种（如 {@code IDR}）
 * @param channel  渠道（{@code QRIS} …），供网关选 payment_type
 * @param purpose  用途（审计/网关备注用，绝不含 PII）
 */
public record ChargeRequest(String orderId, long amount, String currency, String channel, String purpose) {
}
