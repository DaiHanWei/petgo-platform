package com.tailtopia.admin.moderation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * L0：A2 被举报用户工作台的筛选解析（V1.3.0 Story 2.5 AC1 / AC8）：两态、举报类型、关键词、页码、
 * 旧 {@code ?status=} 兼容、{@code HX-Current-URL} 还原、处置后回待处置第一页。
 */
class TicketFiltersTest {

    @Test
    void ofMapsStateAndLegacyStatus() {
        assertThat(TicketFilters.of(null, null, null, null, null)).isEqualTo(TicketFilters.DEFAULT);
        assertThat(TicketFilters.of("handled", null, null, null, null).handled()).isTrue();
        assertThat(TicketFilters.of(null, "RESOLVED", null, null, null).handled()).isTrue();
        assertThat(TicketFilters.of(null, "NO_ACTION", null, null, null).handled()).isTrue();
        assertThat(TicketFilters.of(null, "PENDING", null, null, null).handled()).isFalse();
        assertThat(TicketFilters.of("pending", "RESOLVED", null, null, null).handled()).isFalse();
        TicketFilters f = TicketFilters.of("pending", null, " SPAM ", " 88 ", -2);
        assertThat(f.reason()).isEqualTo("SPAM");
        assertThat(f.q()).isEqualTo("88");
        assertThat(f.page()).isZero();
        assertThat(f.state()).isEqualTo("pending");
    }

    @Test
    void fromUrlRestoresAndFallsBack() {
        TicketFilters f = TicketFilters.fromUrl("https://api-stag.tailtopia.id/admin/tickets?state=handled&reason=HARASSMENT&q=%E7%8C%AB&page=2");
        assertThat(f.handled()).isTrue();
        assertThat(f.reason()).isEqualTo("HARASSMENT");
        assertThat(f.q()).isEqualTo("猫");
        assertThat(f.page()).isEqualTo(2);
        // 处置后：回待处置第一页，其余筛选保留（AC8「下一条」口径）
        TicketFilters next = f.pendingFirstPage();
        assertThat(next.handled()).isFalse();
        assertThat(next.page()).isZero();
        assertThat(next.reason()).isEqualTo("HARASSMENT");
        assertThat(next.q()).isEqualTo("猫");

        assertThat(TicketFilters.fromUrl(null)).isEqualTo(TicketFilters.DEFAULT);
        assertThat(TicketFilters.fromUrl("/admin/tickets")).isEqualTo(TicketFilters.DEFAULT);
        assertThat(TicketFilters.fromUrl("::bad::")).isEqualTo(TicketFilters.DEFAULT);
        assertThat(TicketFilters.fromUrl("/admin/tickets?page=x").page()).isZero();
        assertThat(f.withPage(5).page()).isEqualTo(5);
    }

    @Test
    void rowHelpers() {
        UnifiedTicketRow pending = new UnifiedTicketRow(TicketType.ACCOUNT_REPORT, 7L, null, 2L, "n", false,
                TicketStatusBucket.PENDING, 3, 7, 1, 12, Instant.now(), null, null, null, 0);
        TicketQueueRow r = new TicketQueueRow(pending, null, null, null, false);
        assertThat(r.pending()).isTrue();
        assertThat(r.dot()).isEqualTo("hot");
        assertThat(r.batchToken()).isEqualTo("ACCOUNT_REPORT:7");
        UnifiedTicketRow done = new UnifiedTicketRow(TicketType.ACCOUNT_REPORT, 8L, null, 2L, "n", false,
                TicketStatusBucket.RESOLVED, 1, 1, 0, 1, Instant.now(), null, null, null, 1);
        assertThat(new TicketQueueRow(done, "WARNING", "运营", Instant.now(), false).dot()).isEqualTo("done");
        assertThat(new TicketDisposeResult("9", 8L, 3, 4).rowId()).isEqualTo("ticket-row-8");
    }
}
