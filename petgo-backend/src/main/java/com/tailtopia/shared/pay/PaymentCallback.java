package com.tailtopia.shared.pay;

import java.util.Map;

/**
 * 网关回调/轮询归一化结果（Story 1.1）。回调与轮询两通道都产出本类型，交
 * {@code PaymentIntentService} 单一收口幂等推进意图（双通道只推进一次）。
 *
 * @param orderId    对外订单号（= public_token，回调 {@code order_id}）
 * @param gatewayRef 网关订单号（{@code transaction_id}，库级去重键）
 * @param status     网关侧结果四态
 * @param rawMeta    脱敏快照（落 gateway_meta；绝不含签名/凭证）
 */
public record PaymentCallback(String orderId, String gatewayRef, GatewayStatus status,
        Map<String, Object> rawMeta) {
}
