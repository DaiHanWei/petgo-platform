package com.tailtopia.shop.logistics;

import java.time.Instant;
import java.util.List;

/**
 * 报价结果（v1.4.2 shop Story 1.1）。{@code quoteToken} 对外（不可枚举）；{@code rates} 按价格升序。
 * 邮编格式不对的「直接不可配送」结果不落库，{@code quoteToken} / {@code expiresAt} 为 null。
 */
public record QuoteResult(String quoteToken, boolean serviceable, List<CourierRate> rates,
        Long lowestPrice, String destPostalCode, String itemsHash, Instant expiresAt) {

    public QuoteResult {
        rates = rates == null ? List.of() : List.copyOf(rates);
    }
}
