package com.tailtopia.order.dto;

import java.time.Instant;

/**
 * 统一订单卡片视图（Story 5.1，泛化 4 类）。**不下发 title/subtitle 显示串**——前端按 {@code orderType}+{@code statusCode}
 * 本地化（i18n 契约，App 不渲染后端串）。{@code amount} 可空（HD/待接单预留；本 story 3 类恒非 null）。
 *
 * @param orderType   订单类型（VET_CONSULT/AI_UNLOCK/PAWCOIN_TOPUP/ID_HD）
 * @param orderToken  对外不可枚举订单号（详情用）
 * @param statusCode  状态码（前端本地化 + 5-3 详情分支）
 * @param statusColor 状态色语义（WARN/INFO/SUCCESS；退款中 INFO）
 * @param amount      金额 IDR（可空——泛化预留；3 类恒非 null）
 * @param payChannel  支付渠道（QRIS/PAWCOIN；可空）
 * @param createdAt   建单时间（跨源合并排序键）
 */
public record OrderSummaryView(
        String orderType,
        String orderToken,
        String displayNo,
        String statusCode,
        String statusColor,
        Long amount,
        String payChannel,
        Instant createdAt) {
}
