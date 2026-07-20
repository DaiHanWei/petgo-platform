package com.tailtopia.pay.refund.dto;

import java.util.List;

/**
 * 用户端「我的退款」视图（Story 4.5）。**零 PII 回显**：绝不含 {@code payout_account}/{@code account_holder_name}
 * 明文（即使本人），只回 {@link #payoutFilled} 表是否已填。退款方式由订单原支付渠道决定（PawCoin→即时退币 / QRIS→真钱）。
 *
 * @param refundToken    对外不可枚举 token
 * @param orderToken     关联订单 token
 * @param payChannel     订单原支付渠道（{@code PAWCOIN}/{@code QRIS}）
 * @param refundMethod   退款方式（{@code INSTANT_PAWCOIN} 原路即时退币 / {@code REAL_MONEY} 填真钱账户）
 * @param needDecision   客服判定（{@code PENDING}/{@code APPROVED}/{@code REJECTED}）
 * @param approvalStatus 第二段审批态（可空；QRIS 提交后 {@code PENDING_APPROVAL}，PawCoin 即时退后 {@code DONE}）
 * @param orderAmount    订单金额（IDR）
 * @param actionable     可选退款方式（{@code needDecision=APPROVED} 且未提交/未退）
 * @param payoutFilled   是否已填收款/已退（{@code approvalStatus} 非空）
 * @param payoutOptions  QRIS 出款渠道费预览（BCA/OVO/GoPay + fee + net；PawCoin 为空列表）
 * @param pawcoinCreditPreview 转 PawCoin 到账预览（koin）：QRIS/DANA=退款额+bonus溢价；PawCoin 原路=退款额
 */
public record MyRefundView(
        String refundToken,
        String orderToken,
        String payChannel,
        String refundMethod,
        String needDecision,
        String approvalStatus,
        long orderAmount,
        boolean actionable,
        boolean payoutFilled,
        List<PayoutOption> payoutOptions,
        long pawcoinCreditPreview) {

    /**
     * 出款渠道净额预览（后端权威 {@code net = orderAmount − fee}，前端仅展示不回传，FR-NFR-5）。
     *
     * @param channel 渠道（BCA/OVO/GOPAY）
     * @param fee     渠道费（IDR）
     * @param net     到手净额（IDR）
     */
    public record PayoutOption(String channel, long fee, long net) {
    }
}
