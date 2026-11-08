package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 1.1 · 报价清理阈值与批量上限。 */
class ShippingQuoteCleanupJobTest {

    @Test
    @DisplayName("删除阈值 = now − 2 天，单次上限 5,000 行，返回删除数")
    void deletesOlderThanTwoDays() {
        ShippingQuoteRecordRepository repo = mock(ShippingQuoteRecordRepository.class);
        when(repo.deleteCreatedBefore(any(), anyInt())).thenReturn(42);
        Instant now = Instant.parse("2026-11-05T20:30:00Z");
        ShippingQuoteCleanupJob job = new ShippingQuoteCleanupJob(repo, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(job.run()).isEqualTo(42);
        verify(repo).deleteCreatedBefore(Instant.parse("2026-11-03T20:30:00Z"), 5_000);
    }
}
