package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** L0：SQL 拆分器与参数传递（Story 4.1 T2）。 */
class SqlRollupFamilyTest {

    @Test
    void splitsOnSemicolonsOutsideStringsAndComments() {
        String script = """
                -- 注释里有分号; 和 'quote
                DELETE FROM t WHERE a = ';' ; /* 块注释; */
                INSERT INTO t VALUES ('it''s; fine', "we;ird") -- 尾注释;
                ;
                ;
                SELECT 1""";
        List<String> s = SqlRollupFamily.splitStatements(script);
        assertThat(s).containsExactly("DELETE FROM t WHERE a = ';'",
                "INSERT INTO t VALUES ('it''s; fine', \"we;ird\")", "SELECT 1");
    }

    @Test
    void unclosedQuoteFails() {
        assertThatThrownBy(() -> SqlRollupFamily.splitStatements("SELECT 'x")).isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void rejectsBadNames() {
        assertThatThrownBy(() -> new SqlRollupFamily("../etc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqlRollupFamily("activity", 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void computeDayPassesDatesFromJava() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RollupContext ctx = new RollupContext(LocalDate.parse("2026-11-20"), Instant.parse("2026-11-20T20:00:00Z"),
                List.of(), AnalyticsRollupProperties.defaults(), jdbc);
        SqlRollupFamily f = new SqlRollupFamily("install");
        f.computeDay(LocalDate.parse("2026-11-19"), ctx.asOf(), ctx);

        // NamedParameterJdbcTemplate 把命名参数换成 ? 后交给底层 JdbcTemplate：两条语句各执行一次
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(2)).update(psc.capture());
        String first = ((SqlProvider) psc.getAllValues().get(0)).getSql();
        assertThat(first).startsWith("DELETE FROM agg_daily_install").doesNotContain(":stat_date");

        MapSqlParameterSource p = ctx.dayParams(LocalDate.parse("2026-11-19"));
        assertThat(p.getValue("stat_date")).isEqualTo(LocalDate.parse("2026-11-19"));
        assertThat(p.getValue("as_of")).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(p.getValue("lookback_start")).isEqualTo(LocalDate.parse("2026-08-19"));
        assertThat(p.getValue("computed_at")).isInstanceOf(OffsetDateTime.class);
        assertThat(((SqlParameterSource) p).hasValue("stat_date")).isTrue();
        verify(jdbc, times(0)).execute(eq(RollupContext.CREATE_TMP_SQL));
    }

    @Test
    void propertiesValidateTimeout() {
        assertThat(AnalyticsRollupProperties.defaults().statementTimeout()).isEqualTo("600s");
        assertThat(new AnalyticsRollupProperties(true, "-", " 10min ", 92, 92).statementTimeout()).isEqualTo("10min");
        assertThatThrownBy(() -> new AnalyticsRollupProperties(true, "-", "600s'; DROP", 92, 92))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalyticsRollupProperties(true, "-", "600s", 0, 92))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
