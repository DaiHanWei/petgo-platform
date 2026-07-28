package com.tailtopia.admin.payment.dto;

import java.time.Instant;

/** 支付记录行（Story 9.6，AB-8E，跨类型只读）。displayNo 为可读支付号（bug 326，与 App 支付页同源同串）。 */
public record AdminPaymentRow(
        long userId,
        String publicToken,
        String displayNo,
        String purpose,
        String channel,
        long amount,
        String currency,
        String status,
        Instant createdAt,
        String createdAtLabel) {
}
