package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * L0：每个指标族的 SQL 都能从 classpath 读到、不推算当前日期、只按 event_date 定日、只用框架提供的参数
 * （Story 4.1 AC2 / T8；架构「处理规则」「反例」）。新指标族把名字加进 {@link #FAMILIES}。
 */
class RollupSqlFilesTest {

    /** 已注册的 SQL 指标族（与 {@link RollupConfig} 一致；dau_reconcile 的口径断言见 {@code DauReconcileFamilyTest}）。 */
    static final List<String> FAMILIES = List.of("activity", "install", "dau_reconcile");

    private static final Set<String> ALLOWED_PARAMS = Set.of("stat_date", "as_of", "lookback_start", "computed_at");
    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-z_][a-z0-9_]*)");

    @ParameterizedTest
    @ValueSource(strings = {"activity", "install"})
    void readableAndNoCurrentDateFunctions(String family) {
        String sql = SqlRollupFamily.readScript(family);
        String lower = sql.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("now()", "current_date", "current_timestamp", "localtimestamp",
                "clock_timestamp", "transaction_timestamp", "statement_timestamp");
        // 反例：按 UTC 切天 / 按收到时间定日
        assertThat(lower).doesNotContain("date(event_time", "event_time::date", "received_at");
        assertThat(lower).contains("event_date");
        assertThat(lower).contains("v_events_person").contains("tmp_excluded_users");
        // 标准过滤
        assertThat(lower).contains("is_production").contains("not p.is_suspicious").contains("source = 'app'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity", "install"})
    void onlyFrameworkParameters(String family) {
        for (String stmt : new SqlRollupFamily(family).statements()) {
            Matcher m = PARAM.matcher(stmt);
            while (m.find()) {
                assertThat(ALLOWED_PARAMS).as("%s 参数 :%s", family, m.group(1)).contains(m.group(1));
            }
            assertThat(stmt).contains(":stat_date");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity", "install"})
    void deleteThenInsertSameDay(String family) {
        List<String> stmts = new SqlRollupFamily(family).statements();
        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0)).startsWith("DELETE FROM agg_daily_" + family + " WHERE stat_date = CAST(:stat_date AS date)");
        assertThat(stmts.get(1)).startsWith("INSERT INTO agg_daily_" + family);
        // 汇总表不写可识别个人的列
        assertThat(stmts.get(1).lines().findFirst().orElseThrow()).doesNotContain("user_id", "anonymous_id",
                "person_key");
    }

    @Test
    void allRegisteredFamiliesHaveFiles() {
        for (String f : FAMILIES) {
            assertThat(SqlRollupFamily.resourcePath(f)).isEqualTo("analytics/rollup/" + f + ".sql");
            assertThat(SqlRollupFamily.readScript(f)).isNotBlank();
        }
        assertThatThrownBy(() -> SqlRollupFamily.readScript("no_such_family"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void installFirstsUseLookbackAndExcludeUpdated() {
        String sql = new SqlRollupFamily("install").statements().get(1);
        assertThat(sql).contains("BETWEEN CAST(:lookback_start AS date) AND CAST(:stat_date AS date)")
                .contains("'Application Installed'").contains("'signup_succeeded'")
                .doesNotContain("Application Updated");
    }

    @Test
    void activityAllRowIsSeparateDistinct() {
        String sql = new SqlRollupFamily("activity").statements().get(1);
        assertThat(sql).contains("GROUPING SETS ((o.platform), ())").contains("count(DISTINCT o.person_key)")
                .contains("count(DISTINCT o.user_id)").contains("'Application Opened'");
    }
}
