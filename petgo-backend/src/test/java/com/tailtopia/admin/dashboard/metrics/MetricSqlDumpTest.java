package com.tailtopia.admin.dashboard.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.dashboard.domain.MetricScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * L0：把 18 项口径 SQL（含 REAL 双口径，共 27 份）原样导出到 {@code target/metric-sql/<key>__<SCOPE>.sql}
 * （V1.3.0 Story 3.5 AC3 / D-26）。stag 验收脚本 {@code scripts/local/verify_dashboard_parity.sh} 用它们对只读库直跑「口径 SQL 直跑值」列，
 * 保证对照用的 SQL 与线上物化用的是<b>同一份</b>（Java 类），不是手抄的参考 SQL。命名参数 {@code :d} 由脚本替换成日期字面量。
 */
class MetricSqlDumpTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    private List<AbstractMetricQuery> all() {
        return List.of(new NewUsersMetricQuery(jdbc), new CumulativeUsersMetricQuery(jdbc), new PostingUsersMetricQuery(jdbc),
                new NewPostsMetricQuery(jdbc), new NewPetOwnersMetricQuery(jdbc), new CumulativePetOwnersMetricQuery(jdbc),
                new DiaryPetOwnersMetricQuery(jdbc), new InteractedPostsMetricQuery(jdbc), new SilentPostsMetricQuery(jdbc),
                new EngagementScoreMetricQuery(jdbc), new NewPostsScoreMetricQuery(jdbc), new AllPostsAvgScoreMetricQuery(jdbc),
                new InteractedPostsAvgScoreMetricQuery(jdbc), new EngagementScoreAllTimeMetricQuery(jdbc),
                new PayingUsersCashMetricQuery(jdbc), new PaymentsCashMetricQuery(jdbc),
                new PayingUsersInclPawcoinMetricQuery(jdbc), new PaymentsInclPawcoinMetricQuery(jdbc));
    }

    @Test
    void dumpsEveryMetricScopeSqlForTheParityScript() throws IOException {
        Path dir = Path.of("target", "metric-sql");
        Files.createDirectories(dir);
        int written = 0;
        for (AbstractMetricQuery q : all()) {
            for (MetricScope scope : q.key().scopes()) {
                String sql = q.sql(scope);
                assertThat(sql).contains(":" + MetricSql.PARAM_DAY);
                Files.writeString(dir.resolve(q.key().key() + "__" + scope.name() + ".sql"), sql + "\n", StandardCharsets.UTF_8);
                written++;
            }
        }
        assertThat(written).isEqualTo(27); // 18 项，其中 9 项双口径
    }
}
