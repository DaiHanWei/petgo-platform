package com.tailtopia.profile.slot.dto;

import java.time.Instant;

/**
 * {@code GET /api/v1/pet-slots/me/purchases/{purchaseToken}}（V1.4.0 multi-pet Story 2.2 · AC8.5）：App {@code pollPaid} 轮询。
 *
 * @param status    PENDING / PAID / FAILED / EXPIRED
 * @param expiresAt 本单发放后涉及名额的最晚到期日（D9 / D12 SnackBar 的 {@code {date}}）；未发放为 null
 */
public record PetSlotPurchaseStatusView(String status, Instant expiresAt) {
}
