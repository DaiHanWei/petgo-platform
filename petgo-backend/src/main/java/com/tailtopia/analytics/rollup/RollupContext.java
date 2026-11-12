package com.tailtopia.analytics.rollup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 一次汇总运行的上下文（analytics-pipeline Story 4.1）。
 *
 * <h2>排除用户（通用规则「用户维度只算 USER + REAL」，Q5）</h2>
 * 明细层只把「非 USER / 非 REAL 用户<b>自己</b>的事件」标成非生产；匿名事件按关联表（D-10）归到兽医 / 虚拟号时，生产标记管不到，
 * 必须在按人归属之后再排除一次。做法：
 * <ol>
 *   <li>每次运行开始，{@link #load} 用<b>业务库</b> {@code JdbcTemplate} 窄查询 {@link #EXCLUDED_USERS_SQL}，结果只存在本对象里。</li>
 *   <li>每个单元事务内 {@link #createExcludedUsersTable()} 在<b>埋点库</b>建 {@code tmp_excluded_users}（{@code ON COMMIT DROP}）
 *       并一次性插入；汇总 SQL {@code LEFT JOIN tmp_excluded_users x ON x.user_id = p.person_user_id … WHERE x.user_id IS NULL}。</li>
 * </ol>
 * 两库之间从不跨库 SQL：数据经 Java 中转。
 *
 * <p>埋点连接池只有 3 条：本对象不持有连接；{@link #jdbc()} 在 {@link RollupService} 的单元事务内使用时自动复用事务连接。
 * 日志不打用户 id。
 */
public final class RollupContext {

    /** 业务库窄查询：非 USER 角色或非 REAL 账号（{@code ck_users_role}：USER / VET / ADMIN；{@code ck_users_account_type}：REAL / VIRTUAL）。 */
    static final String EXCLUDED_USERS_SQL = "SELECT id FROM users WHERE role <> 'USER' OR account_type <> 'REAL'";
    static final String CREATE_TMP_SQL =
            "CREATE TEMP TABLE tmp_excluded_users (user_id bigint PRIMARY KEY) ON COMMIT DROP";
    static final String FILL_TMP_SQL = "INSERT INTO tmp_excluded_users (user_id) SELECT DISTINCT unnest(?::bigint[])";

    private final LocalDate asOf;
    private final Instant runAt;
    private final Long[] excludedUserIds;
    private final AnalyticsRollupProperties props;
    private final JdbcTemplate analyticsJdbc;
    private final NamedParameterJdbcTemplate named;

    RollupContext(LocalDate asOf, Instant runAt, List<Long> excludedUserIds, AnalyticsRollupProperties props,
            JdbcTemplate analyticsJdbc) {
        this.asOf = asOf;
        this.runAt = runAt;
        this.excludedUserIds = excludedUserIds.toArray(Long[]::new);
        this.props = props;
        this.analyticsJdbc = analyticsJdbc;
        this.named = new NamedParameterJdbcTemplate(analyticsJdbc);
    }

    /** 运行开始时读一次业务库排除用户集合（在任何埋点事务之外调用）。 */
    static RollupContext load(JdbcTemplate businessJdbc, JdbcTemplate analyticsJdbc, AnalyticsRollupProperties props,
            LocalDate asOf, Instant runAt) {
        List<Long> ids = businessJdbc.queryForList(EXCLUDED_USERS_SQL, Long.class);
        return new RollupContext(asOf, runAt, ids, props, analyticsJdbc);
    }

    /** 本次运行的基准日（WIB 昨天）。 */
    public LocalDate asOf() {
        return asOf;
    }

    /** 本次运行开始时刻（UTC），写进各 {@code agg_*} 表的 {@code computed_at}。 */
    public Instant runAt() {
        return runAt;
    }

    public AnalyticsRollupProperties properties() {
        return props;
    }

    /** 埋点库命名参数模板（只在单元事务内用）。 */
    public NamedParameterJdbcTemplate jdbc() {
        return named;
    }

    /** 埋点库模板（只在单元事务内用）。 */
    public JdbcTemplate jdbcTemplate() {
        return analyticsJdbc;
    }

    /** 排除用户数（只用于日志）。 */
    public int excludedUserCount() {
        return excludedUserIds.length;
    }

    /** 「首次」回看起点 = {@code statDate − install-lookback-days}。 */
    public LocalDate lookbackStart(LocalDate statDate) {
        return statDate.minusDays(props.installLookbackDays());
    }

    /**
     * 单日 SQL 的标准参数：{@code stat_date}、{@code as_of}、{@code lookback_start}、{@code computed_at}。
     * 指标族可在返回值上继续 {@code addValue}。
     */
    public MapSqlParameterSource dayParams(LocalDate statDate) {
        return new MapSqlParameterSource()
                .addValue("stat_date", statDate)
                .addValue("as_of", asOf)
                .addValue("lookback_start", lookbackStart(statDate))
                .addValue("computed_at", OffsetDateTime.ofInstant(runAt, ZoneOffset.UTC));
    }

    /** 在当前单元事务内建 {@code tmp_excluded_users} 并插入排除用户（事务提交 / 回滚即消失）。 */
    void createExcludedUsersTable() {
        analyticsJdbc.execute(CREATE_TMP_SQL);
        if (excludedUserIds.length > 0) {
            analyticsJdbc.update(FILL_TMP_SQL,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", excludedUserIds)));
        }
    }

    /** 测试用：排除用户 id 副本。 */
    List<Long> excludedUserIds() {
        return Arrays.asList(excludedUserIds.clone());
    }
}
