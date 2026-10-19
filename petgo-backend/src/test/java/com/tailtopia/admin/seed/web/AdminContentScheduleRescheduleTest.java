package com.tailtopia.admin.seed.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.AdminSchedulePageService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * L0：改期接口只接受「排期中」的行（L2 第四轮：已发布的行改期未被拦截）。
 * 已发布 / 已取消排期（回到草稿）/ 待确认的行 → 422 {@code admin.err.schedule.notReschedulable}，且不落库；
 * 失败行仍走原有的「先回工作台修好」提示。
 */
class AdminContentScheduleRescheduleTest {

    private final SeedBatchRowRepository rows = mock(SeedBatchRowRepository.class);
    private final AdminContentScheduleController controller = new AdminContentScheduleController(rows,
            mock(SeedBatchService.class), mock(AdminSchedulePageService.class), mock(Messages.class));

    /** 明天同一时刻（WIB 墙上时间），保证在未来。 */
    private static final String TOMORROW = LocalDateTime.now(ZoneId.of("Asia/Jakarta")).plusDays(1)
            .truncatedTo(ChronoUnit.MINUTES).toString();

    private static SeedBatchRow row(SeedBatchRowStatus target) {
        SeedBatchRow r = SeedBatchRow.draft(1L, 1, 7L, ContentType.DAILY, null, "x", null, null);
        if (target == SeedBatchRowStatus.DRAFT) {
            return r;
        }
        r.transitionTo(SeedBatchRowStatus.VALIDATED);
        if (target == SeedBatchRowStatus.VALIDATED) {
            return r;
        }
        r.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS));
        r.transitionTo(SeedBatchRowStatus.SCHEDULED);
        if (target == SeedBatchRowStatus.PUBLISHED) {
            r.setContentPostId(99L);
            r.transitionTo(SeedBatchRowStatus.PUBLISHED);
        } else if (target == SeedBatchRowStatus.FAILED) {
            r.transitionTo(SeedBatchRowStatus.FAILED);
        }
        return r;
    }

    private void assertRejected(SeedBatchRowStatus status, String code) {
        SeedBatchRow r = row(status);
        Instant before = r.getScheduledAt();
        when(rows.findById(5L)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> controller.doReschedule(5L, TOMORROW))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo(code));
        assertThat(r.getScheduledAt()).isEqualTo(before);
        verify(rows, never()).save(any());
    }

    @Test
    void publishedRowCannotBeRescheduled() {
        assertRejected(SeedBatchRowStatus.PUBLISHED, "admin.err.schedule.notReschedulable");
    }

    @Test
    void cancelledRowBackInDraftCannotBeRescheduled() {
        assertRejected(SeedBatchRowStatus.DRAFT, "admin.err.schedule.notReschedulable");
    }

    @Test
    void validatedButNeverScheduledRowCannotBeRescheduled() {
        assertRejected(SeedBatchRowStatus.VALIDATED, "admin.err.schedule.notReschedulable");
    }

    @Test
    void failedRowKeepsItsFixFirstMessage() {
        assertRejected(SeedBatchRowStatus.FAILED, "admin.err.schedule.failedRowFixFirst");
    }

    @Test
    void scheduledRowIsRescheduled() {
        SeedBatchRow r = row(SeedBatchRowStatus.SCHEDULED);
        when(rows.findById(5L)).thenReturn(Optional.of(r));
        controller.doReschedule(5L, TOMORROW);
        Instant expected = LocalDateTime.parse(TOMORROW).atZone(ZoneId.of("Asia/Jakarta")).toInstant();
        assertThat(r.getScheduledAt()).isEqualTo(expected);
        assertThat(r.getStatus()).isEqualTo(SeedBatchRowStatus.SCHEDULED);
        verify(rows).save(r);
    }
}
