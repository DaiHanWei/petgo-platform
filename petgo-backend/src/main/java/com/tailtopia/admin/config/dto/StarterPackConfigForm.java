package com.tailtopia.admin.config.dto;

/**
 * 「超值礼包」配置卡表单（V1.5.0 gift-pack Story 5-1 · AC1 · D-21）：三档价（IDR = PawCoin，≥100 且 ≤
 * {@code AdminConfigService.MAX_TIER_AMOUNT}）+ 总开关。改价只影响之后的新购买，待付单按下单快照。
 */
public record StarterPackConfigForm(long tier1Price, long tier2Price, long tier3Price, boolean enabled) {
}
