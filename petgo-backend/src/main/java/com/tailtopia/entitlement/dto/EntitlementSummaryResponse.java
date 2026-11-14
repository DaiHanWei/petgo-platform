package com.tailtopia.entitlement.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/me/entitlements}（V1.5.0 gift-pack Story 4-4 · AC2 · 架构 §4.2）：只列剩余 &gt; 0；
 * family 顺序固定 {@code KTP_HD, TAILSONALITY, GIFT_1K}；KTP 的 {@code templates} 按 {@code CreditType} 声明序（8 款在前、ANY 最后），
 * 其余 family 的 {@code templates} 为空数组。{@code expiringSoon} = 最早到期 ≤ now + 7 天。
 */
public record EntitlementSummaryResponse(int totalRemaining, List<Family> types) {

    public record Family(String family, int remaining, Instant earliestExpiry, boolean expiringSoon,
            List<Template> templates) {
    }

    /** {@code template} = KTP / PASSPORT / KITAS / SIM / STUDENT_BINTANG / … / ANY。 */
    public record Template(String template, int remaining, Instant earliestExpiry) {
    }
}
