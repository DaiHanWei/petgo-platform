package com.tailtopia.admin.dashboard.service;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 看板取数（V1.3.0 Story 3.4 AC2）：<b>只读 {@code ops_daily_metrics}，不实时算</b>（AD-2 / AD-9）。
 * 一次 {@code findByReportDateBetweenOrderByReportDate} → 按 (key, scope) 分组 → 对齐到范围内每一天 → 五张卡。
 * D-30：只对<b>不存在的行</b>填 {@code null}（断点），存在行即使 value = 0 也照传 0。
 */
@Service
public class DashboardQueryService {

    /** 时间范围只有两档（2026-09-01 拍板）。 */
    public static final Set<Integer> RANGES = Set.of(7, 30);
    public static final int DEFAULT_RANGE = 7;

    /** 卡与指标映射（PRD ①-a 分组）：用户增长 {1,2}；建档转化 {5,6,7}；内容生产 {3,4}；互动质量 {8..14}；付费 {15,16 | 17,18}。 */
    static final List<CardSpec> CARDS = List.of(
            new CardSpec("users", List.of(DashboardMetric.NEW_USERS, DashboardMetric.CUMULATIVE_USERS), false, false),
            new CardSpec("pets", List.of(DashboardMetric.NEW_PET_OWNERS, DashboardMetric.CUMULATIVE_PET_OWNERS,
                    DashboardMetric.DIARY_PET_OWNERS), false, false),
            new CardSpec("content", List.of(DashboardMetric.POSTING_USERS, DashboardMetric.NEW_POSTS), true, false),
            new CardSpec("engagement", List.of(DashboardMetric.INTERACTED_POSTS, DashboardMetric.SILENT_POSTS,
                    DashboardMetric.ENGAGEMENT_SCORE, DashboardMetric.NEW_POSTS_SCORE, DashboardMetric.ALL_POSTS_AVG_SCORE,
                    DashboardMetric.INTERACTED_POSTS_AVG_SCORE, DashboardMetric.ENGAGEMENT_SCORE_ALL_TIME), true, false),
            new CardSpec("payment", List.of(DashboardMetric.PAYING_USERS_CASH, DashboardMetric.PAYMENTS_CASH,
                    DashboardMetric.PAYING_USERS_INCL_PAWCOIN, DashboardMetric.PAYMENTS_INCL_PAWCOIN), false, true));

    record CardSpec(String id, List<DashboardMetric> metrics, boolean scopeTabs, boolean payTabs) {
    }

    private final OpsDailyMetricRepository repository;
    private final ObjectMapper json;

    public DashboardQueryService(OpsDailyMetricRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    /** {@code range} 只接受 7 / 30，否则 422（{@code admin.err.dashboard.badRange}）。 */
    public static int rangeOrThrow(Integer range) {
        if (range == null || !RANGES.contains(range)) {
            throw AppException.validation("时间范围只支持近 7 天或近 30 天").code("admin.err.dashboard.badRange");
        }
        return range;
    }

    /** 整页首屏：非法 / 缺省 range 回默认 7 天（不报错）。 */
    public static int rangeOrDefault(Integer range) {
        return range != null && RANGES.contains(range) ? range : DEFAULT_RANGE;
    }

    /** 请求参数原文（非数字 / 缺省 / 不在两档内 → 默认 7 天；不让 {@code ?range=abc} 变成 400）。 */
    public static int rangeOrDefault(String raw) {
        return rangeOrDefault(parse(raw));
    }

    /** 请求参数原文（非数字 / 不在两档内 → 422）。 */
    public static int rangeOrThrow(String raw) {
        return rangeOrThrow(parse(raw));
    }

    private static Integer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 取图表数据。{@code includePayment=false}（登录者无 {@code payment.view} 且非 SUPER_ADMIN，Story 3.5 / D-17）时<b>不查、不下发</b>付费卡：
     * JSON 内嵌在页面源码里，只在模板层藏卡片数据仍会泄露，服务端必须先把付费四项从结果里拿掉。其余四卡不受影响。
     */
    @Transactional(readOnly = true)
    public ChartData chartData(int rangeDays, boolean includePayment) {
        int range = rangeOrThrow(rangeDays);
        LocalDate end = LocalDate.now(ScheduleWindow.WIB).minusDays(1);
        LocalDate start = end.minusDays(range - 1L);
        List<LocalDate> days = new ArrayList<>(range);
        List<String> labels = new ArrayList<>(range);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            days.add(d);
            labels.add(d.toString());
        }
        Map<String, Map<LocalDate, BigDecimal>> byKeyScope = new HashMap<>();
        for (OpsDailyMetric row : repository.findByReportDateBetweenOrderByReportDate(start, end)) {
            byKeyScope.computeIfAbsent(row.getMetricKey() + "/" + row.getScope().name(), k -> new HashMap<>())
                    .put(row.getReportDate(), row.getValue());
        }
        List<ChartCard> cards = new ArrayList<>(CARDS.size());
        for (CardSpec spec : CARDS) {
            if (spec.payTabs() && !includePayment) {
                continue; // D-17：无财务查看权 → 付费卡整卡不下发（数据一行都不进响应）
            }
            cards.add(card(spec, days, labels, byKeyScope));
        }
        return new ChartData(range, start, end, List.copyOf(labels), List.copyOf(cards));
    }

    private ChartCard card(CardSpec spec, List<LocalDate> days, List<String> labels, Map<String, Map<LocalDate, BigDecimal>> byKeyScope) {
        List<ChartSeries> series = new ArrayList<>();
        boolean[] hasAny = new boolean[days.size()];
        for (DashboardMetric m : spec.metrics()) {
            List<MetricScope> scopes = m.dualScope() ? List.of(MetricScope.ALL, MetricScope.REAL) : List.of(MetricScope.ALL);
            for (MetricScope scope : scopes) {
                Map<LocalDate, BigDecimal> byDay = byKeyScope.getOrDefault(m.key() + "/" + scope.name(), Map.of());
                List<BigDecimal> values = new ArrayList<>(days.size());
                for (int i = 0; i < days.size(); i++) {
                    BigDecimal v = byDay.get(days.get(i));
                    values.add(v);
                    if (v != null) {
                        hasAny[i] = true;
                    }
                }
                series.add(new ChartSeries(m.key(), scope.name(), values));
            }
        }
        int missing = 0;
        for (boolean b : hasAny) {
            if (!b) {
                missing++;
            }
        }
        List<String> keys = spec.metrics().stream().map(DashboardMetric::key).toList();
        return new ChartCard(spec.id(), keys, spec.scopeTabs(), spec.payTabs(), List.copyOf(series), missing,
                missing == days.size(), toJson(labels, series));
    }

    /** {@code {"labels":[…],"series":[{"key","scope","values":[12,null,9.5,…]}]}}；数值去掉 NUMERIC(18,4) 的尾零。 */
    private String toJson(List<String> labels, List<ChartSeries> series) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("labels", labels);
        List<Map<String, Object>> out = new ArrayList<>(series.size());
        for (ChartSeries s : series) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("key", s.key());
            o.put("scope", s.scope());
            List<Number> vals = new ArrayList<>(s.values().size());
            for (BigDecimal v : s.values()) {
                vals.add(num(v));
            }
            o.put("values", vals);
            out.add(o);
        }
        root.put("series", out);
        // Boot 4 = Jackson 3（tools.jackson）：容器里只有它的 ObjectMapper bean；写出失败抛 JacksonException（运行时）
        return json.writeValueAsString(root);
    }

    static Number num(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? (Number) s.longValueExact() : (Number) s.doubleValue();
    }
}
