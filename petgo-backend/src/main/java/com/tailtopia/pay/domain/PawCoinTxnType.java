package com.tailtopia.pay.domain;

/**
 * PawCoin 用户流水类型（Story 1.2，落库 varchar(16) + CHECK）。
 * {@code TOPUP} 充值到账、{@code SPEND} 消费扣减、{@code REFUND} 退回、{@code BONUS} 赠送（决策 C-1：仅未交付转 PawCoin）。
 */
public enum PawCoinTxnType {
    TOPUP,
    SPEND,
    REFUND,
    BONUS
}
