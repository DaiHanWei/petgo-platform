package com.tailtopia.shared.pay;

import java.util.Map;

/**
 * 收款创建结果（Story 1.1）。{@code gatewayRef} = 网关订单号（Midtrans {@code transaction_id}），
 * 回调/轮询双通道去重的库级权威键；{@code payload} = 前端付款载荷（QRIS 二维码串 / e-wallet deeplink）。
 * {@code rawMeta} 为脱敏快照，落 {@code gateway_meta} JSONB（绝不含凭证/签名）。
 *
 * @param gatewayRef 网关订单号（回调据此定位；唯一）
 * @param payload    付款载荷（二维码串 / deeplink）
 * @param rawMeta    脱敏原始快照
 */
public record ChargeResult(String gatewayRef, String payload, Map<String, Object> rawMeta) {
}
