package com.tailtopia.triage.domain;

/**
 * 用户选择的解锁方式（Story 2.3，{@code POST /triage/{id}/unlock} 请求）。映射到 unlock_source/channel + 支付路径：
 * <ul>
 *   <li>{@code FREE_QUOTA}：每月免费额度（2-1 tryConsume）→ {@code unlock_source=FREE_QUOTA}，无订单、无支付。</li>
 *   <li>{@code PAWCOIN}：站内余额扣（同步）→ {@code unlock_source=PAID, unlock_channel=PAWCOIN} + COMPLETED 订单。</li>
 *   <li>{@code QRIS}：Midtrans 现金收款（异步）→ 建 payment_intent + PENDING_PAYMENT 订单，到账后解锁。</li>
 * </ul>
 *
 * <p><b>DANA 已取消</b>（2026-07-13 产品决策）。
 */
public enum UnlockMethod {
    FREE_QUOTA,
    PAWCOIN,
    QRIS
}
