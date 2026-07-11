package com.tailtopia.pay.domain;

/**
 * 总账科目（Story 1.2，落库 varchar(20) + CHECK）。双分录记账的账户维度。
 *
 * <ul>
 *   <li>{@code CASH_IN}：平台收到的现金（Midtrans 收款）。</li>
 *   <li>{@code FLOAT_LIABILITY}：欠用户的 PawCoin 浮存负债（= 用户钱包余额的总账镜像，对账基准）。</li>
 *   <li>{@code VET_PAYABLE} / {@code VET_PAID}：兽医应付 / 已付（计提在 3.7、月结打款在 9.5，本 story 仅建枚举）。</li>
 *   <li>{@code PLATFORM_REVENUE}：平台确认的收入（用户消费 PawCoin 时）。</li>
 *   <li>{@code REFUND_OUT}：退款流出（Epic 4）。</li>
 * </ul>
 */
public enum LedgerAccount {
    CASH_IN,
    FLOAT_LIABILITY,
    VET_PAYABLE,
    VET_PAID,
    PLATFORM_REVENUE,
    REFUND_OUT
}
