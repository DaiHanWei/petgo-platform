package com.tailtopia.pay.refund.dto;

/**
 * 转 PawCoin 退款结果（Story 4.5 + 0718 成功页）。供 App 成功页展示金额明细 + 新余额。
 * 零 PII。退款仅针对兽医订单（orderType 恒 VET_CONSULT），故不下发订单类型（前端本地化）。
 *
 * @param baseAmount     基础退款额（= 订单金额，koin）
 * @param bonusAmount    转币溢价 bonus（koin；PawCoin 原路退为 0）
 * @param totalCredited  实际到账（= base + bonus，koin）
 * @param newBalance     退款后 PawCoin 余额（koin）
 */
public record RefundPawcoinResult(
        long baseAmount,
        long bonusAmount,
        long totalCredited,
        long newBalance) {
}
