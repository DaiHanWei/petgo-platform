package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.content.repository.ContentPostViewRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * L0：按天浏览写入（V1.4.2 social-topic · Story 4.1 · AC8 · D-3 / P-6）。mock 仓储，不连库。
 *
 * <p>固定时钟取 UTC 2026-11-04 18:30 —— 此刻 WIB（UTC+7）已是 11-05：断言日期按 WIB 取、不是 UTC 日。
 */
class ContentViewStatsServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T18:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate WIB_TODAY = LocalDate.of(2026, 11, 5);

    private final ContentPostViewRepository views = mock(ContentPostViewRepository.class);
    private final ContentViewStatsService service = new ContentViewStatsService(views, CLOCK);

    @Test
    void loggedInViewerWritesDayRowWithWibDate() {
        service.recordView(42L, 7L, null);
        verify(views).upsertView(eq(42L), eq("u:7"), any());
        verify(views).insertViewDay(42L, WIB_TODAY, "u:7");
    }

    @Test
    void anonymousViewerWithSessionWritesDayRow() {
        service.recordView(42L, null, "abc_123-XYZ");
        verify(views).insertViewDay(42L, WIB_TODAY, "a:abc_123-XYZ");
    }

    @Test
    void anonymousWithoutSessionWritesNothing() {
        service.recordView(42L, null, null);
        service.recordView(42L, null, "!!!");
        verifyNoInteractions(views);
    }

    @Test
    void dayRowFailureIsSwallowedAndUpsertStillRuns() {
        doThrow(new RuntimeException("db down")).when(views).insertViewDay(anyLong(), any(), anyString());
        assertThatCode(() -> service.recordView(42L, 7L, null)).doesNotThrowAnyException();
        // 两句各自事务、各自 try：按天表失败不影响累计表（后台两列口径）。
        verify(views).upsertView(eq(42L), eq("u:7"), any());
    }

    @Test
    void upsertFailureIsSwallowedAndDayRowStillWritten() {
        doThrow(new RuntimeException("db down")).when(views).upsertView(anyLong(), anyString(), any());
        assertThatCode(() -> service.recordView(42L, 7L, null)).doesNotThrowAnyException();
        verify(views).insertViewDay(42L, WIB_TODAY, "u:7");
    }

    @Test
    void recordViewOpensNoOuterTransaction() throws Exception {
        // 外层事务会让一句失败把另一句一起回滚（rollback-only）—— 事务只放在仓储方法上。
        var m = ContentViewStatsService.class.getMethod("recordView", long.class, Long.class, String.class);
        assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        for (String name : new String[] {"upsertView", "insertViewDay"}) {
            boolean annotated = java.util.Arrays.stream(ContentPostViewRepository.class.getMethods())
                    .filter(x -> x.getName().equals(name))
                    .allMatch(x -> x.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
            assertThat(annotated).as(name).isTrue();
        }
    }

    @Test
    void cleanupJobCutoffIsWibTodayMinus30AndSwallowsFailure() {
        ContentViewDayCleanupJob job = new ContentViewDayCleanupJob(views, CLOCK);
        job.cleanup();
        verify(views).deleteViewDaysBefore(WIB_TODAY.minusDays(30));

        doThrow(new RuntimeException("db down")).when(views).deleteViewDaysBefore(any());
        assertThatCode(job::cleanup).doesNotThrowAnyException();
    }
}
