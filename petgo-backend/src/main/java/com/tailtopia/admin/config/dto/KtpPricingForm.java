package com.tailtopia.admin.config.dto;

/**
 * 「KTP 模块高清图解锁定价」三行表单（V1.3.0 Story 6.1，AB-18A）：KTP 卡高清下载 / 护照·护照内页 / 护照·登机牌。
 * 三价独立保存、不联动；一律 ≥1（D-7 不做 0 元限免，DB CHECK 同口径）。
 */
public record KtpPricingForm(long idHdDownloadPrice, long passportPagePrice, long passportBoardingPrice) {
}
