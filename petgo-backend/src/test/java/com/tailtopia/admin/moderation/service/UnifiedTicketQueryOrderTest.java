package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0：统一工单队列的排序子句（bug 20260921-498）。真实排序效果由
 * {@code UnifiedTicketQueryIntegrationTest#handledViewSortsByHandledAtDesc}（L1）钉死。
 */
class UnifiedTicketQueryOrderTest {

    @Test
    void handledViewOrdersByHandledAtDescNullsLast() {
        String order = UnifiedTicketQueryService.orderBy(new UnifiedTicketQueryService.Extra(null, null, true));
        assertThat(order).startsWith(" ORDER BY u.handled_at DESC NULLS LAST");
        assertThat(order).doesNotContain("earliest_at");
    }

    @Test
    void pendingViewKeepsPendingFirstThenScoreThenEarliest() {
        String order = UnifiedTicketQueryService.orderBy(UnifiedTicketQueryService.Extra.NONE);
        assertThat(order).contains("CASE u.status_bucket WHEN 'PENDING' THEN 0 ELSE 1 END")
                .contains("u.score DESC, u.earliest_at ASC")
                .doesNotContain("handled_at");
        assertThat(UnifiedTicketQueryService.orderBy(null)).isEqualTo(order);
    }
}
