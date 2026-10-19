package com.tailtopia.admin.seed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** L0：改计划时间落库并写审计 SEED_ROW_RESCHEDULE（L2 收尾：排期 / 取消都留审计，改时间原先漏记）。 */
class SeedBatchServiceRescheduleAuditTest {

    private final SeedBatchRowRepository rows = mock(SeedBatchRowRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final SeedBatchService service = new SeedBatchService(mock(SeedBatchRepository.class), rows, audit);

    private static SeedBatchRow scheduled(Instant at) {
        SeedBatchRow r = SeedBatchRow.draft(1L, 1, 7L, ContentType.DAILY, null, "x", null, null);
        r.transitionTo(SeedBatchRowStatus.VALIDATED);
        r.setScheduledAt(at);
        r.transitionTo(SeedBatchRowStatus.SCHEDULED);
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", 5L);
        return r;
    }

    @Test
    void rescheduleUpdatesConditionallyAndAuditsWithOldAndNewTime() {
        Instant from = Instant.now().plus(1, ChronoUnit.HOURS);
        SeedBatchRow r = scheduled(from);
        Instant to = from.plus(1, ChronoUnit.DAYS);
        when(rows.rescheduleIfInStatus(eq(5L), eq(SeedBatchRowStatus.SCHEDULED), eq(to), any())).thenReturn(1);

        service.reschedule(r, to, 3L);

        verify(rows).rescheduleIfInStatus(eq(5L), eq(SeedBatchRowStatus.SCHEDULED), eq(to), any());
        verify(rows, never()).save(any()); // OCR #3：不整行写回（会把并发置的 PUBLISHED 覆盖回 SCHEDULED）
        verify(audit).record(eq(3L), eq("SEED_ROW_RESCHEDULE"), eq("seed_batch_row"), eq("5"),
                eq("from=" + from + ";to=" + to));
    }

    /** OCR #3：查完之后行被到点扫描发布了 → 条件更新影响 0 行 → 422，不写审计。 */
    @Test
    void rowPublishedConcurrentlyIsRejectedWithoutAudit() {
        SeedBatchRow r = scheduled(Instant.now().plus(1, ChronoUnit.HOURS));
        when(rows.rescheduleIfInStatus(anyLong(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reschedule(r, Instant.now().plus(2, ChronoUnit.DAYS), 3L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.schedule.notReschedulable"));
        verify(rows, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }
}
