package com.tailtopia.pay.event;

/**
 * PawCoin 消费扣款成功领域事件（V1.4.0 ① Story 1.3 · 架构 AD-8，过去式）。
 *
 * <p>由 {@code PawCoinWalletService#debit} 在 {@code type = SPEND} 且<b>真正扣款成功</b>后、同一事务内发布；
 * 幂等重放短路<b>不发</b>。消费方：① 纯币消费返成长值（AFTER_COMMIT）。
 *
 * @param userId         扣款用户
 * @param coins          扣了多少币（1 PawCoin = Rp1）
 * @param refType        业务类型（与 {@code PaymentPurpose} 同名）
 * @param refId          业务单号
 * @param idempotencyKey 本次 debit 的幂等键
 */
public record PawCoinSpentEvent(long userId, long coins, String refType, Long refId, String idempotencyKey) {
}
