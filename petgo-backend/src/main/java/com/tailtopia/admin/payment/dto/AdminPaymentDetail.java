package com.tailtopia.admin.payment.dto;

import java.time.Instant;

/**
 * B12 支付记录抽屉（V1.3.0 Story 8.5 · AC1）：一条支付意图的只读全字段。
 *
 * <p>🔴 **不带 {@code gatewayMeta}**：那是网关回调的原始快照，虽然落库前已脱敏，
 * 但它是一个「以后会被塞进新字段」的开放结构 —— 今天没有敏感项，不代表明天没有。
 * 后台要回答的问题（这笔钱是什么、多少、什么状态、什么时候）用下面这些字段就够了；
 * 真要查网关侧原始记录，用 {@code gatewayRef} 去网关后台查，那里才是权威。
 *
 * @param gatewayRef  网关订单号 —— 财务对账时拿它跟网关流水核对，是本抽屉存在的主要理由之一
 * @param coinAmount  混合支付的 PawCoin 段（非混合为 null）
 * @param cashAmount  混合支付的现金段（非混合为 null）。🔴 现金收入只认这一段，见 AdminPaymentSummary
 * @param expiresAt   付款窗过期时刻（无窗为 null）
 */
public record AdminPaymentDetail(
        long userId, String publicToken, String displayNo, String purpose, String channel,
        long amount, String currency, String status, String gatewayRef,
        Long coinAmount, Long cashAmount, Instant expiresAt,
        Instant createdAt, Instant updatedAt) {

    /** 终态（PAID / FAILED / EXPIRED）——终态不可再被回调推进（服务层的幂等闸）。 */
    public boolean terminal() {
        return "PAID".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
    }
}
