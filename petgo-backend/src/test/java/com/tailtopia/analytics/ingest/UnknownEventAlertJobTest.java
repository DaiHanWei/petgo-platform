package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.ingest.UnknownEventAlertJob.EventCount;
import com.tailtopia.analytics.ingest.UnknownEventAlertJob.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：analytics-pipeline Story 1.4 AC4 —— 告警文案、SQL 不用 now()、管道停用跳过。查库与锁见 L1。 */
class UnknownEventAlertJobTest {

    private UnknownEventAlertJob job(AnalyticsPipelineState state) {
        return new UnknownEventAlertJob(state, mock(JdbcTemplate.class), mock(TransactionTemplate.class),
                mock(OpsAlertService.class), "test", Clock.fixed(Instant.parse("2026-11-13T01:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("SQL 日期全部参数化：不出现 now() / CURRENT_DATE，按事件名计数前 50")
    void queryHasNoServerClock() {
        String sql = UnknownEventAlertJob.QUERY.toLowerCase();
        assertThat(sql).doesNotContain("now()").doesNotContain("current_date")
                .contains("received_at >= ?").contains("received_at < ?").contains("unknown_event")
                .contains("group by event").contains("limit 50");
    }

    @Test
    @DisplayName("文案：环境、日期、事件名与条数；控制字符替换")
    void text() {
        String t = job(new AnalyticsPipelineState()).text(LocalDate.of(2026, 11, 12),
                List.of(new EventCount("foo_tapped", 12), new EventCount("bad\nname", 1)));

        assertThat(t).startsWith("【埋点未登记事件】环境 test").contains("2026-11-12").contains("foo_tapped：12")
                .contains("bad?name：1");
    }

    @Test
    @DisplayName("库不可用 → 跳过")
    void skippedWhenDbUnavailable() {
        AnalyticsPipelineState state = new AnalyticsPipelineState();
        state.disable(Reason.DB_UNAVAILABLE, "x");

        assertThat(job(state).run(LocalDate.of(2026, 11, 12))).isEqualTo(Outcome.SKIPPED_DISABLED);
    }
}
