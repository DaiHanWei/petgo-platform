package com.tailtopia.admin.dailyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：日报编排 —— 切日、未配置跳过、定时吞异常、手动触发抛业务异常。 */
class DailyReportServiceTest {

    private final DailyReportQuery query = mock(DailyReportQuery.class);
    private final LarkWebhookClient lark = mock(LarkWebhookClient.class);
    private final DailyReportProperties props = new DailyReportProperties();

    /** 2026-09-25 02:00 UTC = 09:00 WIB。 */
    private final Clock at9Wib = Clock.fixed(Instant.parse("2026-09-25T02:00:00Z"), ZoneOffset.UTC);

    private static final DailyReport.Metrics ZERO = new DailyReport.Metrics(0, null, 0, 0, 0, 0, 0, 0, 0, 0);

    private DailyReportService service() {
        return new DailyReportService(query, lark, props, at9Wib);
    }

    @Test
    @DisplayName("统计「昨天」、环比「前天」")
    void reportsYesterdayAgainstDayBefore() {
        when(query.metricsOf(any())).thenReturn(ZERO);

        DailyReport r = service().getDailyReport(LocalDate.of(2026, 9, 25));

        assertThat(r.date()).isEqualTo(LocalDate.of(2026, 9, 24));
        verify(query).metricsOf(LocalDate.of(2026, 9, 24));
        verify(query).metricsOf(LocalDate.of(2026, 9, 23));
    }

    @Test
    @DisplayName("🔴 发送日按 WIB 取：UTC 17:30 已是 WIB 次日 00:30 → 统计的是 WIB 的「昨天」")
    void todayIsWib() {
        props.setWebhookUrl("http://x");
        when(query.metricsOf(any())).thenReturn(ZERO);
        Clock lateUtc = Clock.fixed(Instant.parse("2026-09-24T17:30:00Z"), ZoneOffset.UTC);

        DailyReport r = new DailyReportService(query, lark, props, lateUtc).pushNow();

        assertThat(r.date()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    @DisplayName("webhook 未配置 → 定时任务静默跳过，不查库不发送")
    void scheduledSkipsWhenNotConfigured() {
        service().pushScheduled();

        verifyNoInteractions(query, lark);
    }

    @Test
    @DisplayName("🔴 定时任务：取数或发送抛任何异常都只记日志，不抛出（推送失败不影响主业务）")
    void scheduledSwallowsEverything() {
        props.setWebhookUrl("http://x");
        when(query.metricsOf(any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> service().pushScheduled()).doesNotThrowAnyException();

        org.mockito.Mockito.doReturn(ZERO).when(query).metricsOf(any());
        doThrow(new LarkWebhookClient.LarkWebhookException("HTTP 500")).when(lark).send(any());
        assertThatCode(() -> service().pushScheduled()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("手动触发：未配置 → 业务异常（接口能回显原因）")
    void pushNowNotConfigured() {
        assertThatThrownBy(() -> service().pushNow())
                .isInstanceOf(AppException.class).hasMessageContaining("未配置");
        verify(lark, never()).send(any());
    }

    @Test
    @DisplayName("手动触发：发送失败 → 业务异常带原因")
    void pushNowFailure() {
        props.setWebhookUrl("http://x");
        when(query.metricsOf(any())).thenReturn(ZERO);
        doThrow(new LarkWebhookClient.LarkWebhookException("飞书 webhook 返回非 0 code: 19021")).when(lark).send(any());

        assertThatThrownBy(() -> service().pushNow())
                .isInstanceOf(AppException.class).hasMessageContaining("19021");
    }
}
