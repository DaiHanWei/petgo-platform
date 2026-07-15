package com.tailtopia.shared.pay;

/**
 * 发起出款请求（Story 4.6，退款真钱打款经 Midtrans Iris/Disbursement）。对称 {@link ChargeRequest}（收款）。
 * {@code amount} 为净额（用户实收，最小币种单位 IDR 整型）。{@code payoutAccount}/{@code accountHolderName}
 * 为**敏感 PII**（明文，仅用于出款调用）——实现<b>绝不 log</b>，服务层从加密列解密后传入。
 *
 * @param refundRef         对外退款标识（= refund_requests.refund_token，网关幂等/对账用）
 * @param amount            出款净额（最小币种单位整型）
 * @param currency          币种（如 {@code IDR}）
 * @param channel           出款渠道（{@code BCA}/{@code OVO}/{@code GOPAY}），供网关选 bank/e-wallet
 * @param payoutAccount     收款账号（PII，绝不 log）
 * @param accountHolderName 户名（PII，绝不 log）
 */
public record DisburseRequest(String refundRef, long amount, String currency, String channel,
        String payoutAccount, String accountHolderName) {
}
