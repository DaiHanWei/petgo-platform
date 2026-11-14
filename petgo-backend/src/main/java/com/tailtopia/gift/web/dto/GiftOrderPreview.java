package com.tailtopia.gift.web.dto;

import java.util.List;

/**
 * Diary 礼物行（V1.5.0 gift-pack Story 1-4 · AC2）。
 *
 * @param giftCount     收到的礼物个数（Σ quantity，已送达）
 * @param unopenedCount 仅主人视角；访客为 null
 * @param recent        最多 10 张卡，排序同列表对应视角
 */
public record GiftOrderPreview(long giftCount, Integer unopenedCount, List<GiftOrderCard> recent) {
}
