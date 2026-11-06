package com.tailtopia.shop.order.refund;

import java.util.Locale;
import java.util.Optional;

/** 打款失败原因（V1.4.2 shop-admin Story 5.2 · AD-10 · E4）：账户信息有误 / 转账被退回 / 其他。 */
public enum PayoutFailReason {
    ACCOUNT_INVALID,
    TRANSFER_RETURNED,
    OTHER;

    public static Optional<PayoutFailReason> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
