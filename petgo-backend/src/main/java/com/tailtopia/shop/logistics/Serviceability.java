package com.tailtopia.shop.logistics;

/**
 * 可配送判定（v1.4.2 shop Story 1.1，FR-172.1 / D-9）。
 *
 * @param quote {@code SERVICEABLE} / {@code NO_COURIER}（询过价时）带报价；未询价为 null
 */
public record Serviceability(Status status, QuoteResult quote) {

    public enum Status {
        SERVICEABLE,
        /** 该 Kecamatan 未开通（不存在或 active=false）。 */
        ZONE_NOT_OPEN,
        /** 已开通但无快递可达（或游客所选区无代表邮编）。 */
        NO_COURIER
    }

    public static Serviceability of(QuoteResult quote) {
        return new Serviceability(quote.serviceable() ? Status.SERVICEABLE : Status.NO_COURIER, quote);
    }
}
