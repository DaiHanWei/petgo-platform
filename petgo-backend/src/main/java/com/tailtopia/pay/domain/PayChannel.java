package com.tailtopia.pay.domain;

/**
 * 支付渠道（Story 1.1，落库 varchar(16) + CHECK）。{@code QRIS} 走 Midtrans 收款；
 * {@code PAWCOIN} 为站内余额扣减（Story 1.2/1.3，无外部收款）。
 *
 * <p><b>DANA 已取消</b>（2026-07-13 产品决策：不做 DANA 支付）。历史迁移的 CHECK 仍列 DANA 为无害残留
 * （枚举不再产生此值，DB permissive 不影响）。
 *
 * <p>{@code MIXED}（V1.4.0 Story 3.3）：一笔订单 PawCoin 段 + QRIS 段并存，
 * 拆分金额落在 {@code payment_intents.coin_amount / cash_amount}，
 * 不变式 {@code coin + cash = amount} 由 DB CHECK 强制（AD-1）。
 *
 * <p>🔴 <b>MIXED 只在末尾追加</b>（并行契约 E-1）：不重排、不删除、不改既有两值拼写——
 * 枚举序数会进 DB 的场合，重排等于静默改写历史数据的含义。
 */
public enum PayChannel {
    QRIS,
    PAWCOIN,
    /** 混合支付：PawCoin 段 + 现金段（V1.4.0 Story 3.3，仅电商订单使用）。 */
    MIXED
}
