package com.tailtopia.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.entitlement.domain.CreditFamily;
import com.tailtopia.entitlement.service.EntitlementExpiryGrouping;
import com.tailtopia.entitlement.service.EntitlementExpiryGrouping.Batch;
import com.tailtopia.entitlement.service.EntitlementExpiryGrouping.Group;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 4-4 AC4.7 —— 分组、取最早 WIB 日期、n 计算、WIB 跨日边界。 */
class EntitlementExpiryGroupingTest {

    @Test
    void groupsByUserAndFamilyAndCountsEarliestWibDayOnly() {
        List<Group> g = EntitlementExpiryGrouping.group(List.of(
                new Batch(1, 7, CreditFamily.KTP_HD, 1, Instant.parse("2026-11-20T05:00:00Z")),
                new Batch(2, 7, CreditFamily.KTP_HD, 2, Instant.parse("2026-11-20T09:00:00Z")),
                new Batch(3, 7, CreditFamily.KTP_HD, 4, Instant.parse("2026-11-21T05:00:00Z")),
                new Batch(4, 7, CreditFamily.GIFT_1K, 5, Instant.parse("2026-11-19T01:00:00Z")),
                new Batch(5, 8, CreditFamily.KTP_HD, 1, Instant.parse("2026-11-22T01:00:00Z"))));
        assertThat(g).hasSize(3);
        Group ktp = g.stream().filter(x -> x.userId() == 7 && x.family() == CreditFamily.KTP_HD).findFirst()
                .orElseThrow();
        assertThat(ktp.date()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(ktp.count()).isEqualTo(3);
        assertThat(ktp.creditIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        Group gift = g.stream().filter(x -> x.family() == CreditFamily.GIFT_1K).findFirst().orElseThrow();
        assertThat(gift.count()).isEqualTo(5);
        assertThat(g.stream().filter(x -> x.userId() == 8).count()).isEqualTo(1);
    }

    @Test
    void wibDayBoundary() {
        // 2026-11-20T16:59Z = WIB 20 日 23:59；17:00Z = WIB 21 日 00:00 → 不同日
        List<Group> g = EntitlementExpiryGrouping.group(List.of(
                new Batch(1, 7, CreditFamily.TAILSONALITY, 1, Instant.parse("2026-11-20T16:59:00Z")),
                new Batch(2, 7, CreditFamily.TAILSONALITY, 1, Instant.parse("2026-11-20T17:00:00Z"))));
        assertThat(g).hasSize(1);
        assertThat(g.get(0).date()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(g.get(0).count()).isEqualTo(1);
        assertThat(g.get(0).creditIds()).containsExactlyInAnyOrder(1L, 2L);
    }
}
