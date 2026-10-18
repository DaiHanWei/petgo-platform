package com.tailtopia.profile.slot.domain;

/**
 * 名额购买行状态（V1.4.0 multi-pet Story 2.2 · AD-5）。
 *
 * <ul>
 *   <li>{@code PENDING} 待付；{@code FAILED} / {@code EXPIRED} 付款失败 / 超时（不增加名额）——这两态的行若其意图仍到账，
 *       到账处理照常发放（同键重开时旧行被置 EXPIRED，但旧二维码仍可能付成功，照 {@code KeepsakePurchaseService}）；</li>
 *   <li>{@code PAID} 已付已发放；</li>
 *   <li>{@code ORPHAN_PAID} 已到账但发放失败（保存点回滚，到账不回滚）→ 交人工补发 / 退款；</li>
 *   <li>{@code OVERPAID} 已到账但发放会让生效名额超过 5（不发无用名额）→ 交人工退款。</li>
 * </ul>
 * 已结算（{@link #isSettled}）的行不再处理（到账重放幂等）。
 */
public enum PetSlotPurchaseStatus {
    PENDING,
    PAID,
    FAILED,
    EXPIRED,
    ORPHAN_PAID,
    OVERPAID;

    public boolean isTerminal() {
        return this != PENDING;
    }

    /** 钱已结算（已付并处理过发放）：PAID / ORPHAN_PAID / OVERPAID。 */
    public boolean isSettled() {
        return this == PAID || this == ORPHAN_PAID || this == OVERPAID;
    }
}
