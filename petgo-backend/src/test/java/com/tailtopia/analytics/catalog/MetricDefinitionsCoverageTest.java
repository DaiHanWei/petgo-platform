package com.tailtopia.analytics.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.catalog.MetricDefinitions.MetricDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * L0 · CI 守门：口径文档覆盖全部汇总表与团队编号（analytics-pipeline Story 4.8 AC1 / AC2，FR-26）。
 *
 * <p>随 {@code mvn package} 在 backend CI 跑。新增汇总表（迁移里 {@code CREATE TABLE agg_daily_ / weekly_ / monthly_ / cohort_*}）
 * 而 {@code metric-definitions.yaml} 没有条目在 {@code tables} 里引用它 → 本测试失败，失败信息直接列出缺哪张表 / 哪个编号。
 */
class MetricDefinitionsCoverageTest {

    static final String RULES = "_rules";

    /** Dev Notes「B、C 类指标清单」的全部编号（FR-24 / FR-25 为日活来源与对账）。 */
    static final List<String> REQUIRED_TEAM_REFS = List.of(
            "S1", "S2", "S3", "S4", "S5",
            "市场#1", "市场#2", "市场#3",
            "产品#1", "产品#2", "产品#3", "产品#5",
            "电商#4", "电商#5", "电商#6", "电商#7", "电商#8", "电商#9",
            "内容#1",
            "FR-24", "FR-25");

    /** 汇总表：agg_progress 不是汇总表，不在此列。 */
    static final Pattern AGG_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(agg_(?:daily|weekly|monthly|cohort)_\\w+)");
    static final Pattern ANY_TABLE = Pattern.compile(
            "(?i)CREATE\\s+(?:TABLE|VIEW)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?!IF\\s)([a-z_][a-z0-9_]*)\\s*[(\\s]");

    private static List<MetricDefinition> metrics;
    private static String migrations;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = new ClassPathResource("analytics/metric-definitions.yaml").getInputStream();
                Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            metrics = MetricDefinitions.parse(r, "metric-definitions.yaml");
        }
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/analytics-migration/*.sql");
        assertThat(files).as("埋点库迁移目录为空").isNotEmpty();
        StringBuilder sb = new StringBuilder();
        for (Resource f : files) {
            try (InputStream in = f.getInputStream()) {
                sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        migrations = sb.toString();
    }

    private static List<MetricDefinition> entries() {
        return metrics.stream().filter(m -> !RULES.equals(m.code())).toList();
    }

    private static MetricDefinition byCode(String code) {
        return metrics.stream().filter(m -> m.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("口径缺条目 " + code));
    }

    static Set<String> tablesMatching(Pattern p, String sql) {
        Set<String> out = new TreeSet<>();
        Matcher m = p.matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toLowerCase());
        }
        return out;
    }

    @Test
    void everyAggregateTableIsReferencedByAnEntry() {
        Set<String> aggTables = tablesMatching(AGG_TABLE, migrations);
        assertThat(aggTables).as("迁移里应能扫到汇总表").contains("agg_daily_activity", "agg_daily_ratio");
        Set<String> referenced = metrics.stream().flatMap(m -> m.tables().stream()).collect(Collectors.toSet());
        List<String> missing = aggTables.stream().filter(t -> !referenced.contains(t)).toList();
        assertThat(missing)
                .as("以下汇总表没有被 metric-definitions.yaml 任何条目的 tables 引用（新表必须补口径条目）：%s", missing)
                .isEmpty();
    }

    @Test
    void everyReferencedTableExistsInMigrations() {
        Set<String> created = tablesMatching(ANY_TABLE, migrations);
        Map<String, List<String>> unknown = new LinkedHashMap<>();
        for (MetricDefinition m : metrics) {
            for (String t : m.tables()) {
                if (!created.contains(t)) {
                    unknown.computeIfAbsent(m.code(), k -> new ArrayList<>()).add(t);
                }
            }
        }
        assertThat(unknown).as("tables 引用了埋点库迁移里不存在的表（拼错？）：%s", unknown).isEmpty();
    }

    @Test
    void teamRefsCoverTheBcList() {
        Set<String> refs = entries().stream().map(MetricDefinition::teamRef).filter(r -> r != null)
                .collect(Collectors.toSet());
        List<String> missing = REQUIRED_TEAM_REFS.stream().filter(r -> !refs.contains(r)).toList();
        assertThat(missing).as("以下团队编号没有口径条目：%s", missing).isEmpty();
    }

    @Test
    void requiredFieldsAreFilledAndSqlFilesExist() {
        List<String> problems = new ArrayList<>();
        for (MetricDefinition m : entries()) {
            String c = m.code();
            if (blank(m.teamRef())) {
                problems.add(c + ".team_ref 为空");
            }
            if (m.sources().isEmpty()) {
                problems.add(c + ".sources 为空");
            }
            if (m.tables().isEmpty()) {
                problems.add(c + ".tables 为空");
            }
            if (blank(m.exclusions()) || !m.exclusions().contains(RULES)) {
                problems.add(c + ".exclusions 为空或未引用 _rules");
            }
            if (blank(m.knownBias())) {
                problems.add(c + ".known_bias 为空");
            }
            if (m.changelog().isEmpty()) {
                problems.add(c + ".changelog 为空");
            }
            if (blank(m.sqlPath())) {
                problems.add(c + ".sql_path 为空");
            } else if (!new ClassPathResource(m.sqlPath()).exists()) {
                problems.add(c + ".sql_path 在 classpath 不存在：" + m.sqlPath());
            }
        }
        assertThat(problems).as("口径条目必填字段：%s", problems).isEmpty();
    }

    /** 通用规则条目：排除规则、按人归属、WIB、周期去重、已付款判据、dau_source 用法都写在一处。 */
    @Test
    void rulesEntryHoldsTheCommonRules() {
        MetricDefinition r = byCode(RULES);
        assertThat(r.teamRef()).isNull();
        assertThat(r.definition()).contains("is_production", "is_suspicious", "role = 'USER'", "account_type = 'REAL'",
                "v_events_person", "identity_links", "最近一次", "第一个", "匿名 id", "WIB", "周一至周日",
                "agg_weekly_*", "agg_monthly_*", "绝不把日表人数相加", "算术平均", "shop_orders.paid_at IS NOT NULL",
                "退款 / 取消仍算已付款", "dau_source", "analytics.dau-source", "agg_progress");
        assertThat(r.changelog()).anySatisfy(c -> assertThat(c.change()).contains("Story 4.8"));
    }

    /** AC1：七条已知偏差逐字出现在对应条目（另加各 story 交来的偏差）。 */
    @Test
    void knownDeviationsAppearVerbatim() throws IOException {
        Map<String, List<String>> required = new LinkedHashMap<>();
        List<String> eventBased = List.of("S1", "S1-登录", "S2", "S3", "S5", "S5-推送", "电商#4", "电商#5", "电商#6",
                "电商#7", "电商#8", "电商#9", "产品#1", "产品#2", "产品#5", "市场#1", "市场#2", "市场#3");
        required.put("卸载重装算新装", List.of(RULES, "S2", "产品#1", "市场#1", "市场#2", "市场#3"));
        required.put("iOS installerStore 为 null", concat(RULES, eventBased));
        required.put("员工与测试账号", concat(RULES, eventBased, "S4", "产品#3", "内容#1", "S1-GA4", "S1-对账自建"));
        required.put("晚到超过 3 天不回算", concat(RULES, eventBased, "S4", "产品#3", "内容#1", "S1-对账自建", "S1-对账"));
        required.put("V1.5.0 之前无数据", concat(RULES, eventBased, "S4", "产品#3", "内容#1", "S1-对账自建"));
        required.put("接收数为近似", List.of(RULES, "S5-推送"));
        required.put("购物车跨设备不可观测", List.of(RULES, "电商#7"));
        // 各 story 交来的已知偏差（原文以各 story 为准，这里只抽关键词）
        required.put("升级用户清过旧版数据", List.of(RULES, "S2"));
        required.put("新版覆盖率爬升期人数偏低", concat(RULES, eventBased));
        required.put("没有发送记录的系统推送只有打开数", List.of("S5-推送"));
        required.put("toko_order_payment_succeeded", List.of("电商#6"));
        required.put("覆盖率未达 90%", List.of("产品#2"));
        required.put("无法到达第 7 步", List.of("电商#6"));
        required.put("7 天观察窗", List.of("电商#7"));
        required.put("不扣退款", List.of("电商#9"));
        required.put("payment_intents 没有 paid_at", List.of("产品#3"));
        required.put("可能为负", List.of("内容#1"));
        required.put("退款 / 取消的订单仍算已付款", List.of("电商#6", "产品#2"));
        required.put("700ms", List.of(RULES, "S1"));
        required.put("超过 3 秒", List.of(RULES, "S1"));

        List<String> missing = new ArrayList<>();
        required.forEach((phrase, codes) -> {
            for (String c : codes) {
                String kb = byCode(c).knownBias();
                if (kb == null || !kb.contains(phrase)) {
                    missing.add(c + " 缺「" + phrase + "」");
                }
            }
        });
        assertThat(missing).as("已知偏差未逐字写进条目：%s", missing).isEmpty();
    }

    @Test
    void codesAreUniqueAndYamlLoadsViaComponent() {
        assertThat(metrics.stream().map(MetricDefinition::code).distinct().count()).isEqualTo(metrics.size());
        assertThat(metrics.get(0).code()).as("通用规则条目放第一条，AI 先读到").isEqualTo(RULES);
    }

    private static List<String> concat(String first, List<String> list, String... more) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(list);
        out.addAll(List.of(more));
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
