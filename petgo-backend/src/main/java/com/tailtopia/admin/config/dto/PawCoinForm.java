package com.tailtopia.admin.config.dto;

/** PawCoin 配置提交表单（Story 9.2 + 0718：退款转币溢价新增固定值参数）。 */
public record PawCoinForm(int premiumRate, long premiumFixed, boolean topupPaused) {
}
