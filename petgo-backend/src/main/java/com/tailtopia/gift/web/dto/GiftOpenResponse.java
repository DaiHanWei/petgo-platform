package com.tailtopia.gift.web.dto;

import java.util.List;

/** 拆礼物响应（V1.5.0 gift-pack Story 1-4 · AC4.4）：{@code items} 按送达时刻倒序，{@code totalOwnerExp = Σ ownerExp}。 */
public record GiftOpenResponse(List<GiftOrderDetail> items, int totalOwnerExp) {
}
