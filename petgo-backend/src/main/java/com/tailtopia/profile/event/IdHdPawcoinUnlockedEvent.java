package com.tailtopia.profile.event;

/**
 * 身份证高清图用 PawCoin 解锁成功（KTP 付费埋点用）。
 *
 * <p>PawCoin 渠道不建支付意图、没有 {@code PaymentIntentPaidEvent}，扣款成功这一刻只有
 * {@code IdCardHdService} 知道 —— 由它在同事务内发布，{@code KtpUnlockAnalyticsListener}
 * 在 AFTER_COMMIT 上报（扣款回滚则不报）。QRIS 到账走 {@code PaymentIntentPaidEvent}，不经本事件。
 *
 * @param userId 付款用户
 * @param price  成交价（PawCoin 数，与 IDR 1:1）
 */
public record IdHdPawcoinUnlockedEvent(long userId, long price) {
}
