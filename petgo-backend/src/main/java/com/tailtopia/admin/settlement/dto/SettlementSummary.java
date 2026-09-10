package com.tailtopia.admin.settlement.dto;

/**
 * B13 兽医月结摘要条三格（V1.3.0 Story 8.5 · AC3）：待打款单数 · 待打款金额合计 · 本月已打款。
 *
 * @param pendingCount  {@code PENDING_FINANCE} 的月结条数
 * @param pendingPayout 这些月结的到手金额合计（财务这个月要付出去多少）
 * @param paidThisMonth **本月（WIB）**已打款金额合计（{@code PAID} / {@code ARCHIVED} 的 Σ payout）
 *
 *        <p>⚠️ 「本月」按 <b>WIB</b> 算，与月结 period 的口径一致（{@code VetSettlementService} 刻意
 *        偏离全局 UTC，用的是印尼本地的「每月 1 号」）。这里跟着它，否则月初两天两个数会对不上。
 *        <p>⚠️ 判据是 <b>{@code paidAt}</b> 落在本月，不是 {@code period} 等于本月：
 *        8 月的月结在 9 月初打款，它属于「9 月已打款」——财务问的是「这个月付出去多少」。
 */
public record SettlementSummary(long pendingCount, long pendingPayout, long paidThisMonth) {
}
