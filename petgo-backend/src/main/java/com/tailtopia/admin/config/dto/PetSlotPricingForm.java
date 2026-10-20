package com.tailtopia.admin.config.dto;

/**
 * 「宠物名额月租价格」卡表单（V1.4.0 multi-pet Story 2.2 · AB-34.3 · UI 稿 L7）：每个名额 / 30 天（IDR）。
 * ≥100 且 ≤ {@code AdminConfigService.MAX_TIER_AMOUNT}；只影响之后的新租与续租，已生成的待付单按下单快照。
 */
public record PetSlotPricingForm(long petSlotMonthlyPrice) {
}
