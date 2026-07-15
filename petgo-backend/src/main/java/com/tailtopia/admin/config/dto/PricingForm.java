package com.tailtopia.admin.config.dto;

/** 定价配置提交表单（Story 9.2）。 */
public record PricingForm(
        long vetConsultPrice,
        int vetShareRate,
        long aiUnlockPrice,
        long idHdDownloadPrice,
        int monthlyFreeQuota) {
}
