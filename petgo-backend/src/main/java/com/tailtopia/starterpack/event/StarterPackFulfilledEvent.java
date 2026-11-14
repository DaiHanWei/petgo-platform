package com.tailtopia.starterpack.event;

/**
 * 礼包发放完成（V1.5.0 gift-pack Story 4-2 · AC6.5）：提交后上报 {@code starter_pack_purchased}。
 * 只带枚举与数值（不含任何 PII）。
 */
public record StarterPackFulfilledEvent(long userId, int tier, String channel, int purchaseIndex) {
}
