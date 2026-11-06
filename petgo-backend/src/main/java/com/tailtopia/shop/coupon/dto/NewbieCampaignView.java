package com.tailtopia.shop.coupon.dto;

/**
 * 游客新人券活动（Story 2.2 AC5，D-5）。{@code campaignToken} = 当前新人券模板 public_token，
 * App 按「设备 × campaignToken」本地记一次弹窗。
 */
public record NewbieCampaignView(String campaignToken, String kind, long amount, long minSpend,
        String nameId, String nameEn) {
}
