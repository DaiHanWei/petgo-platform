package com.tailtopia.triage.domain;

/**
 * AI 解锁订单状态（Story 2.3）。落库 varchar UPPER_SNAKE。
 *
 * <ul>
 *   <li>{@code PENDING_PAYMENT}：现金（QRIS）已建 payment_intent、待 Midtrans 到账。此态<b>不解锁</b>。
 *       （相对架构 COMPLETED/ABNORMAL 增此中间态，作现金异步到账的 intent↔triage 关联锚。）</li>
 *   <li>{@code COMPLETED}：解锁完成（PawCoin 同步直接建为此态；现金到账后由 {@code AiUnlockPaidHandler} 置此）。</li>
 *   <li>{@code ABNORMAL}：对账异常（到账但订单缺失 / triage 已被其它路径解锁 / 金额不符等）。记录不 crash，供 9-x 对账。</li>
 * </ul>
 */
public enum AiConsultOrderStatus {
    PENDING_PAYMENT,
    COMPLETED,
    ABNORMAL
}
