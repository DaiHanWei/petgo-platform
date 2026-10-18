package com.tailtopia.growth.web.dto;

import java.util.List;

/** EXP 明细一页（keyset，{@code nextCursor = null} 为末页）。 */
public record GrowthLedgerPage(List<GrowthLedgerRow> items, String nextCursor) {

    public static GrowthLedgerPage empty() {
        return new GrowthLedgerPage(List.of(), null);
    }
}
