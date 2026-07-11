package com.tailtopia.pay.domain;

/**
 * 支付渠道（Story 1.1，落库 varchar(16) + CHECK）。{@code QRIS}/{@code DANA} 走 Midtrans 收款；
 * {@code PAWCOIN} 为站内余额扣减（Story 1.2/1.3，无外部收款）。
 */
public enum PayChannel {
    QRIS,
    DANA,
    PAWCOIN
}
