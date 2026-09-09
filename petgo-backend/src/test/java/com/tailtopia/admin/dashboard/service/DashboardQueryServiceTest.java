package com.tailtopia.admin.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import com.tailtopia.admin.dashboard.domain.OpsDailyMetric;
import com.tailtopia.admin.dashboard.dto.ChartCard;
import com.tailtopia.admin.dashboard.dto.ChartData;
import com.tailtopia.admin.dashboard.dto.ChartSeries;
import com.tailtopia.admin.dashboard.repository.OpsDailyMetricRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * L0：看板取数（V1.3.0 Story 3.4 AC2）：labels = 范围内每一天；未物化日 = null、物化的 0 = 0（D-30）；
 * 五卡与指标映射；双口径卡含 ALL + REAL 序列；缺日计数与整卡空态；range 只接受 7 / 30。
 */
class DashboardQueryServiceTest {

    private final OpsDailyMetricRepository repo = mock(OpsDailyMetricRepository.class);
    private final DashboardQueryService service = new DashboardQueryService(repo, new ObjectMapper());
    private final LocalDate yesterday = LocalDate.now(ScheduleWindow.WIB).minusDays(1);

    private List<OpsDailyMetric> fullDay(LocalDate d, long base) {
        List<OpsDailyMetric> rows = new ArrayList<>();
        for (DashboardMetric m : DashboardMetric.values()) {
            rows.add(OpsDailyMetric.of(d, m, MetricScope.ALL, BigDecimal.valueOf(base).setScale(4), Instant.now()));
            if (m.dualScope()) {
                rows.add(OpsDailyMetric.of(d, m, MetricScope.REAL, BigDecimal.valueOf(base).setScale(4), Instant.now()));
            }
        }
        return rows;
    }

    @Test
    void labelsCoverEveryDayAndMissingDaysAreNullWhileZeroStaysZero() {
        List<OpsDailyMetric> rows = new ArrayList<>();
        rows.addAll(fullDay(yesterday, 100));                 // 昨天：全部物化
        rows.addAll(fullDay(yesterday.minusDays(3), 0));      // 4 天前：全部 = 0（合法的 0）
        when(repo.findByReportDateBetweenOrderByReportDate(any(), any())).thenReturn(rows);

        ChartData data = service.chartData(7, true);

        assertThat(data.rangeDays()).isEqualTo(7);
        assertThat(data.end()).isEqualTo(yesterday);
        assertThat(data.start()).isEqualTo(yesterday.minusDays(6));
        assertThat(data.labels()).hasSize(7).last().isEqualTo(yesterday.toString());
        assertThat(data.cards()).extracting(ChartCard::id).containsExactly("users", "pets", "content", "engagement", "payment");

        ChartCard users = data.card("users");
        ChartSeries newUsers = users.series().get(0);
        assertThat(newUsers.key()).isEqualTo("new_users");
        assertThat(newUsers.scope()).isEqualTo("ALL");
        assertThat(newUsers.values()).hasSize(7);
        assertThat(newUsers.values().get(6)).isEqualByComparingTo("100");
        assertThat(newUsers.values().get(3)).isEqualByComparingTo("0");   // 存在行、值 0 → 0
        assertThat(newUsers.values().get(0)).isNull();                      // 无行 → null
        assertThat(users.missingDays()).isEqualTo(5);
        assertThat(users.empty()).isFalse();
        // JSON：null 与 0 区分，尾零去掉
        assertThat(users.json()).contains("\"labels\":[").contains("\"key\":\"new_users\"").contains("\"scope\":\"ALL\"")
                .contains("[null,null,null,0,null,null,100]").doesNotContain("100.0000");
    }

    @Test
    void dualScopeCardsCarryBothScopesAndPaymentCardHasBothPairs() {
        when(repo.findByReportDateBetweenOrderByReportDate(any(), any())).thenReturn(fullDay(yesterday, 10));
        ChartData data = service.chartData(30, true);
        assertThat(data.labels()).hasSize(30);

        ChartCard content = data.card("content");
        assertThat(content.scopeTabs()).isTrue();
        assertThat(content.payTabs()).isFalse();
        assertThat(content.metricKeys()).containsExactly("posting_users", "new_posts");
        assertThat(content.series()).extracting(ChartSeries::key, ChartSeries::scope)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("posting_users", "ALL"), org.assertj.core.groups.Tuple.tuple("posting_users", "REAL"),
                        org.assertj.core.groups.Tuple.tuple("new_posts", "ALL"), org.assertj.core.groups.Tuple.tuple("new_posts", "REAL"));

        ChartCard engagement = data.card("engagement");
        assertThat(engagement.metricKeys()).hasSize(7);
        assertThat(engagement.series()).hasSize(14);

        ChartCard payment = data.card("payment");
        assertThat(payment.payTabs()).isTrue();
        assertThat(payment.scopeTabs()).isFalse();
        assertThat(payment.series()).extracting(ChartSeries::key).containsExactly("paying_users_cash", "payments_cash",
                "paying_users_incl_pawcoin", "payments_incl_pawcoin");
        assertThat(payment.series()).allSatisfy(s -> assertThat(s.scope()).isEqualTo("ALL"));

        ChartCard pets = data.card("pets");
        assertThat(pets.metricKeys()).containsExactly("new_pet_owners", "cumulative_pet_owners", "diary_pet_owners");
    }

    @Test
    void wholeCardEmptyWhenNothingMaterialized() {
        when(repo.findByReportDateBetweenOrderByReportDate(any(), any())).thenReturn(List.of());
        ChartData data = service.chartData(7, true);
        assertThat(data.cards()).allSatisfy(c -> {
            assertThat(c.empty()).isTrue();
            assertThat(c.missingDays()).isEqualTo(7);
        });
    }

    @Test
    void paymentCardIsNotProducedWithoutPaymentView() {
        // Story 3.5 / D-17：无 payment.view → 付费卡整卡不在结果里（JSON 内嵌页面，服务端就得拿掉），其余四卡照常
        when(repo.findByReportDateBetweenOrderByReportDate(any(), any())).thenReturn(fullDay(yesterday, 100));
        ChartData data = service.chartData(7, false);
        assertThat(data.cards()).extracting(ChartCard::id).containsExactly("users", "pets", "content", "engagement");
        assertThat(data.cards()).noneSatisfy(c -> assertThat(c.json()).contains("paying_users"));
        assertThat(service.chartData(7, true).cards()).extracting(ChartCard::id).contains("payment");
    }

    @Test
    void rangeValidation() {
        assertThatThrownBy(() -> service.chartData(99, true)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.dashboard.badRange"));
        assertThatThrownBy(() -> DashboardQueryService.rangeOrThrow((Integer) null)).isInstanceOf(AppException.class);
        assertThat(DashboardQueryService.rangeOrDefault((Integer) null)).isEqualTo(7);
        assertThat(DashboardQueryService.rangeOrDefault(14)).isEqualTo(7);
        assertThat(DashboardQueryService.rangeOrDefault(30)).isEqualTo(30);
        // 请求参数原文：非数字不 400，整页回默认、fragment 422
        assertThat(DashboardQueryService.rangeOrDefault("abc")).isEqualTo(7);
        assertThat(DashboardQueryService.rangeOrDefault(" 30 ")).isEqualTo(30);
        assertThat(DashboardQueryService.rangeOrDefault((String) null)).isEqualTo(7);
        assertThat(DashboardQueryService.rangeOrThrow("7")).isEqualTo(7);
        assertThatThrownBy(() -> DashboardQueryService.rangeOrThrow("abc")).isInstanceOf(AppException.class);
    }

    @Test
    void numbersStripTrailingZerosButKeepDecimals() {
        assertThat(DashboardQueryService.num(new BigDecimal("12.0000"))).isEqualTo(12L);
        assertThat(DashboardQueryService.num(new BigDecimal("10.0000"))).isEqualTo(10L);
        assertThat(DashboardQueryService.num(new BigDecimal("6.5000"))).isEqualTo(6.5d);
        assertThat(DashboardQueryService.num(null)).isNull();
    }
}
