package com.tailtopia.admin.config.dto;

/**
 * 定价配置提交表单（Story 9.2）。V1.3.0 Story 6.1 起收窄为四项：KTP 卡高清价移到独立卡 {@link KtpPricingForm}（AB-18A）。
 */
public record PricingForm(
        long vetConsultPrice,
        int vetShareRate,
        long aiUnlockPrice,
        int monthlyFreeQuota) {
}
