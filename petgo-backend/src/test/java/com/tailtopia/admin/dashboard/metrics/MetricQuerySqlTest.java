package com.tailtopia.admin.dashboard.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * L0：18 个指标查询类的静态口径守卫（V1.3.0 Story 3.2 AC3 / AC5）：
 * 每指标恰一个类；切日一律显式 WIB（无裸 {@code ::date}）；真实用户口径只引用 3-1 常量（无前缀散写）；
 * 非双口径指标传 REAL 抛 {@link IllegalArgumentException}；SQL 只读（无 INSERT / UPDATE / DELETE，不碰 ops_daily_metrics）。
 */
class MetricQuerySqlTest {

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

    /** 裸 {@code x::date}（不带 AT TIME ZONE）= 随连接时区漂移的切日写法。 */
    private static final Pattern RAW_DATE_CAST = Pattern.compile("[A-Za-z_.]+::date");

    @Test
    void oneQueryPerMetricCoveringAllEighteen() {
        List<AbstractMetricQuery> qs = all();
        assertThat(qs).hasSize(18);
        assertThat(qs.stream().map(MetricQuery::key).collect(Collectors.toSet())).containsExactlyInAnyOrder(DashboardMetric.values());
    }

    @Test
    void everySqlCutsDayInWibAndNeverWrites() {
        for (AbstractMetricQuery q : all()) {
            for (MetricScope scope : q.key().scopes()) {
                String sql = q.sql(scope);
                assertThat(sql).as(q.key() + "/" + scope).contains("AT TIME ZONE 'Asia/Jakarta')::date").contains(":d");
                assertThat(RAW_DATE_CAST.matcher(sql).find()).as("裸 ::date 切日 " + q.key() + "/" + scope + ": " + sql).isFalse();
                assertThat(sql.toUpperCase()).doesNotContain("INSERT ", "UPDATE ", "DELETE ", "OPS_DAILY_METRICS");
                assertThat(sql).doesNotContain("virtual:", "seed-tailtopia-", "admin:");
            }
        }
    }

    @Test
    void realUserMetricsUseTheSingleExitConstant() {
        for (AbstractMetricQuery q : all()) {
            switch (q.key()) {
                case NEW_USERS, CUMULATIVE_USERS, PAYING_USERS_CASH, PAYMENTS_CASH, PAYING_USERS_INCL_PAWCOIN, PAYMENTS_INCL_PAWCOIN ->
                    assertThat(q.sql(MetricScope.ALL)).as(q.key().key()).contains(SyntheticAccountSql.EXCLUDE_WHERE);
                case NEW_PET_OWNERS, CUMULATIVE_PET_OWNERS, DIARY_PET_OWNERS ->
                    assertThat(q.sql(MetricScope.ALL)).as(q.key().key()).doesNotContain("account_type");
                default -> {
                    // 双口径 9 项：ALL 不排除任何人；REAL 同时约束作者（u）与互动者 / 帖子作者（ua / up）
                    assertThat(q.key().dualScope()).isTrue();
                    assertThat(q.sql(MetricScope.ALL)).as(q.key() + " ALL").doesNotContain("account_type");
                    String real = q.sql(MetricScope.REAL);
                    boolean postDriven = switch (q.key()) {
                        case POSTING_USERS, NEW_POSTS, SILENT_POSTS, NEW_POSTS_SCORE, ALL_POSTS_AVG_SCORE -> true;
                        default -> false;
                    };
                    boolean interactionDriven = q.key() != DashboardMetric.POSTING_USERS && q.key() != DashboardMetric.NEW_POSTS;
                    if (postDriven) {
                        // 帖子驱动：作者 JOIN users u
                        assertThat(real).as(q.key() + " REAL author").contains(MetricSql.realUser("u"));
                    }
                    if (interactionDriven) {
                        // 互动驱动：互动者 ua + 帖子作者 up 都要真实，评论按 D-38
                        assertThat(real).as(q.key() + " REAL interactions").contains(MetricSql.realUser("ua"))
                                .contains(MetricSql.realUser("up")).contains(MetricSql.VALID_COMMENT_WHERE);
                    }
                }
            }
        }
    }

    @Test
    void nonDualMetricsRejectRealScopeWithoutTouchingDb() {
        for (AbstractMetricQuery q : all()) {
            if (!q.key().dualScope()) {
                assertThatThrownBy(() -> q.compute(LocalDate.of(2026, 9, 8), MetricScope.REAL))
                        .as(q.key().key()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(q.key().key());
            }
        }
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(BigDecimal.class));
    }

    @Test
    void computeBindsWibDayAsLocalDateAndPassesNullThrough() {
        AbstractMetricQuery q = new AllPostsAvgScoreMetricQuery(jdbc);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(BigDecimal.class))).thenReturn(null);
        assertThat(q.compute(LocalDate.of(2026, 9, 8), MetricScope.REAL)).isNull();
        verify(jdbc).queryForObject(eq(q.sql(MetricScope.REAL)), org.mockito.ArgumentMatchers.argThat(
                (SqlParameterSource p) -> LocalDate.of(2026, 9, 8).equals(p.getValue("d"))), eq(BigDecimal.class));
    }

    @Test
    void interactionsCteFollowsD38AndD29() {
        String all = MetricSql.interactionsCte(MetricScope.ALL);
        assertThat(all).contains("content_likes").contains("moderation_status = 'VISIBLE'").contains("deleted_at IS NULL")
                .doesNotContain("account_type");
        String real = MetricSql.interactionsCte(MetricScope.REAL);
        assertThat(real).contains("JOIN users ua ON ua.id = i.actor_id AND ua.account_type = 'REAL' AND ua.role <> 'ADMIN'")
                .contains("JOIN users up ON up.id = ip.author_id AND up.account_type = 'REAL' AND up.role <> 'ADMIN'");
        assertThat(MetricSql.day("x.created_at")).isEqualTo("(x.created_at AT TIME ZONE 'Asia/Jakarta')::date");
    }
}
