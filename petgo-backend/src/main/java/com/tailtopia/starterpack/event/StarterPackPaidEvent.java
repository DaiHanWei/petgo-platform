package com.tailtopia.starterpack.event;

/** 礼包 QRIS 到账（V1.5.0 gift-pack Story 4-2 · D-24）：回调事务里置 PAID 后发布，提交后由发放监听执行发放。 */
public record StarterPackPaidEvent(long purchaseId) {
}
