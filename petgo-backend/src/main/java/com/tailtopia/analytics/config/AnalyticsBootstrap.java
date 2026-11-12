package com.tailtopia.analytics.config;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 埋点库启动引导（analytics-pipeline Story 1.1，架构「第二套 Flyway」「启动隔离」）。
 *
 * <p>在本 bean 初始化时（{@link InitializingBean}）对埋点库跑第二套 Flyway：只读
 * {@code classpath:db/analytics-migration}，历史表 {@code flyway_schema_history} 落在<b>埋点库</b>内，
 * 占位符 {@code readerRole} 来自 {@code analytics.datasource.reader-role}（迁移里条件授权用，见 Story 1.1 Dev Notes）。
 *
 * <h2>🔴 埋点库出任何问题都不能伤业务</h2>
 * 取不到连接 → {@link Reason#DB_UNAVAILABLE}；迁移失败 → {@link Reason#MIGRATION_FAILED}。两者一律 catch：
 * {@link AnalyticsPipelineState#disable 停用管道}、打 WARN（只打原因枚举、异常类名与 SQLState，<b>不打连接串、账号、密码</b>）、
 * 经 {@link OpsChannel#TECH} 告警（按 UTC 日去重），<b>绝不向外抛</b> —— 后端照常启动、业务接口照常可用。
 *
 * <h2>时序</h2>
 * <ul>
 *   <li>本 bean 注入 {@link OpsAlertService} → 它依赖 JPA 事务管理器 → EntityManagerFactory → 业务 Flyway，
 *       所以跑到这里时业务库迁移已完成（告警去重表 {@code ops_alert_log} 在业务库）。</li>
 *   <li>要求「埋点迁移已跑完」的后续组件（Story 1.2 的清单同步等）用 {@code @DependsOn("analyticsBootstrap")}，
 *       或监听 {@code ApplicationReadyEvent}；执行前先看 {@link AnalyticsPipelineState#isEnabled()}。</li>
 *   <li>埋点 Flyway 刻意<b>不注册成 bean</b>：{@code FlywayAutoConfiguration} 对 {@code Flyway} 类型是
 *       {@code @ConditionalOnMissingBean}，注册了业务迁移就不跑了。</li>
 * </ul>
 */
@Component
public class AnalyticsBootstrap implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsBootstrap.class);

    /** 埋点迁移目录（与业务的 {@code db/migration} 严格分开）。 */
    public static final String MIGRATION_LOCATION = "classpath:db/analytics-migration";
    /** 迁移占位符名：只读角色。 */
    public static final String READER_ROLE_PLACEHOLDER = "readerRole";
    /** 告警去重键前缀，后接 UTC 日期。 */
    static final String ALERT_KEY_PREFIX = "analytics-pipeline-disabled:";

    /** 只读角色名会被拼进迁移 SQL：只接受 PG 普通标识符，挡住 env 写错 / 注入。 */
    private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    /** 取连接探活的超时（秒）；真正的等待上限是连接池的 connection-timeout。 */
    private static final int VALIDATE_TIMEOUT_SECONDS = 5;

    private final DataSource analyticsDataSource;
    private final String readerRole;
    private final String appEnv;
    private final AnalyticsPipelineState state;
    private final OpsAlertService alerts;
    private final Consumer<DataSource> migrator;
    private final Clock clock;

    @Autowired
    public AnalyticsBootstrap(
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) DataSource analyticsDataSource,
            @Value("${analytics.datasource.reader-role:analytics_reader}") String readerRole,
            @Value("${analytics.app-env:dev}") String appEnv,
            AnalyticsPipelineState state,
            OpsAlertService alerts) {
        this(analyticsDataSource, readerRole, appEnv, state, alerts, null, Clock.systemUTC());
    }

    /** 测试用：替换迁移动作与时钟。{@code migrator == null} 时走真实 Flyway。 */
    AnalyticsBootstrap(DataSource analyticsDataSource, String readerRole, String appEnv,
            AnalyticsPipelineState state, OpsAlertService alerts, Consumer<DataSource> migrator, Clock clock) {
        this.analyticsDataSource = analyticsDataSource;
        this.readerRole = readerRole;
        this.appEnv = appEnv;
        this.state = state;
        this.alerts = alerts;
        this.migrator = migrator != null ? migrator : this::migrateWithFlyway;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        bootstrap();
    }

    /** 探活 + 迁移；任何异常都转成停用 + 告警，绝不向外抛。 */
    void bootstrap() {
        try {
            probeConnection();
        } catch (Exception | Error e) {
            fail(Reason.DB_UNAVAILABLE, "埋点库取不到连接", e);
            return;
        }
        try {
            if (readerRole == null || !ROLE_NAME.matcher(readerRole).matches()) {
                throw new FlywayException("analytics.datasource.reader-role 不是合法的 PG 角色名");
            }
            migrator.accept(analyticsDataSource);
            log.info("analytics pipeline enabled: analytics migrations applied");
        } catch (Exception | Error e) {
            fail(Reason.MIGRATION_FAILED, "埋点库迁移失败", e);
        }
    }

    private void probeConnection() throws SQLException {
        try (Connection c = analyticsDataSource.getConnection()) {
            if (!c.isValid(VALIDATE_TIMEOUT_SECONDS)) {
                throw new SQLException("analytics connection is not valid");
            }
        }
    }

    private void migrateWithFlyway(DataSource ds) {
        MigrateResult result = Flyway.configure(AnalyticsBootstrap.class.getClassLoader())
                .dataSource(ds)
                .locations(MIGRATION_LOCATION)
                .table("flyway_schema_history")
                .outOfOrder(true)
                .placeholders(Map.of(READER_ROLE_PLACEHOLDER, readerRole))
                .load()
                .migrate();
        log.info("analytics migrations: {} executed, target version {}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }

    private void fail(Reason reason, String what, Throwable e) {
        String cause = describe(e);
        boolean first = state.disable(reason, what + "（" + cause + "）");
        // 🔒 只打原因枚举与异常类名 / SQLState：异常 message 可能带连接串、库名、账号。
        log.warn("analytics pipeline disabled: reason={} cause={}", reason, cause);
        if (!first) {
            return;
        }
        try {
            String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
            alerts.send(OpsChannel.TECH, ALERT_KEY_PREFIX + day, alertText(reason, what, cause));
        } catch (RuntimeException ex) {
            log.warn("analytics pipeline disabled alert failed: {}", ex.getClass().getSimpleName());
        }
    }

    String alertText(Reason reason, String what, String cause) {
        return "【埋点管道已停用】环境 " + appEnv + "\n"
                + "原因：" + reason + "（" + what + "；" + cause + "）\n"
                + "影响：业务不受影响；埋点接收返回 503、埋点定时任务跳过，期间 App 埋点留在本地队列。\n"
                + "处理：按 docs/runbooks/runbook-analytics-pipeline.md「部署前先建库」核对库 / 账号 / env，"
                + "修复后重建后端容器（docker restart 不重读 env 文件）。";
    }

    /** 异常类名链 + 首个 SQLState，不含 message。 */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        String sqlState = null;
        Throwable t = e;
        int depth = 0;
        while (t != null && depth < 5) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (sqlState == null && t instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
            depth++;
        }
        if (sqlState != null) {
            sb.append(", SQLState ").append(sqlState);
        }
        return sb.toString();
    }
}
