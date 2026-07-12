package com.tailtopia.pay.dto;

/**
 * 充值支付状态（Story 1.5 轮询用）。{@code status} 为 {@code PaymentStatus} 枚举名
 * （PENDING/PAID/FAILED/EXPIRED）。<b>只回状态枚举名，不暴露自增 id / 网关订单号</b>。
 *
 * @param status 支付意图当前状态枚举名
 */
public record TopupStatusView(String status) {
}
