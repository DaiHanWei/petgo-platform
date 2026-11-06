package com.tailtopia.shop.order.pricing;

/**
 * 计价器输入：PawCoin（Story 1.2，D-8「PawCoin 自选」、FR-100A 规则 3/4）。
 *
 * @param useCoin                用户本单是否选择用币
 * @param balance                用户余额
 * @param maxPerOrder            单笔上限（{@code shop_pawcoin_rules.max_coin_per_order}）
 * @param coinEnabled            电商 PawCoin 总开关
 * @param allowShippingDeduction 规则 3：运费（及保价费）是否可用币抵扣
 */
public record CoinOptions(boolean useCoin, long balance, long maxPerOrder,
        boolean coinEnabled, boolean allowShippingDeduction) {
}
