package com.tailtopia.shop.logistics;

/**
 * 派送失败被退回的原因（v1.4.2 shop Story 5.2 AC7.2，PRD §3.10 ②）：运营确认后决定后续处置与是否待索赔。
 *
 * <ul>
 *   <li>{@link #COURIER_FAULT}：快递原因 → {@code claim_pending = true}，运营再快递原因重发新运单（5.1 AC12）；</li>
 *   <li>{@link #USER_FAULT}：用户原因 → 不置 {@code claim_pending}，运营再调 4.1 整单取消（{@code USER_FAULT_RETURNED}，S-8）。</li>
 * </ul>
 */
public enum ReturnCause {
    COURIER_FAULT,
    USER_FAULT
}
