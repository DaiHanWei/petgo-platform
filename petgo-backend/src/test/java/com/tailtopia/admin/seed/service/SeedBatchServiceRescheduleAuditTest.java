package com.tailtopia.admin.seed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** L0：改计划时间落库并写审计 SEED_ROW_RESCHEDULE（L2 收尾：排期 / 取消都留审计，改时间原先漏记）。 */
class SeedBatchServiceRescheduleAuditTest {

    private final SeedBatchRowRepository rows = mock(SeedBatchRowRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final SeedBatchService service = new SeedBatchService(mock(SeedBatchRepository.class), rows, audit);

    @Test
    void rescheduleSavesAndAuditsWithOldAndNewTime() {
        SeedBatchRow r = SeedBatchRow.draft(1L, 1, 7L, ContentType.DAILY, null, "x", null, null);
        r.transitionTo(SeedBatchRowStatus.VALIDATED);
        Instant from = Instant.now().plus(1, ChronoUnit.HOURS);
        r.setScheduledAt(from);
        r.transitionTo(SeedBatchRowStatus.SCHEDULED);
        Instant to = from.plus(1, ChronoUnit.DAYS);

        service.reschedule(r, to, 3L);

        assertThat(r.getScheduledAt()).isEqualTo(to);
        verify(rows).save(r);
        verify(audit).record(eq(3L), eq("SEED_ROW_RESCHEDULE"), eq("seed_batch_row"), eq(String.valueOf(r.getId())),
                eq("from=" + from + ";to=" + to));
    }
}
