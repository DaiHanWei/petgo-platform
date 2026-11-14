package com.tailtopia.entitlement.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementGrantRules;
import com.tailtopia.entitlement.service.TypeSummary;
import com.tailtopia.entitlement.dto.EntitlementSummaryResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 4-4 AC2 —— 汇总响应：family 固定序、KTP 模板声明序（ANY 最后）、剩余 0 不列、expiringSoon。 */
class MeEntitlementControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");

    @Test
    void summaryShape() {
        EntitlementSummaryResponse r = MeEntitlementController.toResponse(EntitlementGrantRules.summarize(List.of(
                new TypeSummary(CreditType.GIFT_1K, 5, NOW.plus(Duration.ofDays(30))),
                new TypeSummary(CreditType.KTP_HD_ANY, 1, NOW.plus(Duration.ofDays(3))),
                new TypeSummary(CreditType.KTP_HD_SIM, 1, NOW.plus(Duration.ofDays(40))),
                new TypeSummary(CreditType.KTP_HD_KTP, 2, NOW.plus(Duration.ofDays(40))),
                new TypeSummary(CreditType.TAILSONALITY, 0, NOW.plus(Duration.ofDays(1))))), NOW);
        assertThat(r.totalRemaining()).isEqualTo(9);
        assertThat(r.types()).extracting(EntitlementSummaryResponse.Family::family).containsExactly("KTP_HD", "GIFT_1K");
        EntitlementSummaryResponse.Family ktp = r.types().get(0);
        assertThat(ktp.remaining()).isEqualTo(4);
        assertThat(ktp.expiringSoon()).isTrue();
        assertThat(ktp.templates()).extracting(EntitlementSummaryResponse.Template::template)
                .containsExactly("KTP", "SIM", "ANY");
        assertThat(r.types().get(1).expiringSoon()).isFalse();
        assertThat(r.types().get(1).templates()).isEmpty();
    }
}
