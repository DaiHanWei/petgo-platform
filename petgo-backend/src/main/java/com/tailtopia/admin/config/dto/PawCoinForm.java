package com.tailtopia.admin.config.dto;

/**
 * PawCoin 配置提交表单（Story 9.2 + 0718：退款转币溢价新增固定值参数）。
 *
 * <p>⚠️ Story 18.3 的分享奖励四项<b>刻意不在这里</b>，另见 {@link ShareRewardForm}：
 * 它们要独立的权限码（关总开关不该顺带要「改兽医定价与分成」的权限）。
 * 两者写的是同一张 {@code pawcoin_config} 单行、同一个 PAWCOIN diff 审计组。
 */
public record PawCoinForm(int premiumRate, long premiumFixed, boolean topupPaused) {
}
