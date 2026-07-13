package com.tailtopia.pay.domain;

/**
 * 支付渠道（Story 1.1，落库 varchar(16) + CHECK）。{@code QRIS} 走 Midtrans 收款；
 * {@code PAWCOIN} 为站内余额扣减（Story 1.2/1.3，无外部收款）。
 *
 * <p><b>DANA 已取消</b>（2026-07-13 产品决策：不做 DANA 支付）。历史迁移的 CHECK 仍列 DANA 为无害残留
 * （枚举不再产生此值，DB permissive 不影响）。
 */
public enum PayChannel {
    QRIS,
    PAWCOIN
}
