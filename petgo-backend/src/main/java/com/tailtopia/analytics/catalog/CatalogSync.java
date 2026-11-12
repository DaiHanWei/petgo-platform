package com.tailtopia.analytics.catalog;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog.EventDefinition;
import com.tailtopia.analytics.catalog.EventCatalog.PropertyDefinition;
import com.tailtopia.analytics.catalog.MetricDefinitions.ChangelogEntry;
import com.tailtopia.analytics.catalog.MetricDefinitions.MetricDefinition;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.sql.Array;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 把两份 yaml 同步进埋点库 {@code event_catalog} / {@code metric_definitions}（analytics-pipeline Story 1.2，D-7 / D-9）。
 *
 * <p><b>yaml 是唯一来源，表只是它的投影</b>：一个事务内逐条 {@code INSERT ... ON CONFLICT DO UPDATE}，
 * 再删掉 yaml 里已不存在的行。事件下线请在 yaml 标 {@code RETIRED}，不要删条目。
 * 内容没变的行不改写（{@code updated_at} 只在真有变化时刷新），重复同步无副作用。
 *
 * <h2>时序与跳过</h2>
 * 在 {@code AnalyticsBootstrap} 迁移之后执行（{@code @DependsOn}）。管道已停用（库不可用 / 迁移失败 / 清单无效）
 * 或任一 yaml 未加载 → 跳过同步；内存快照不受影响。
 *
 * <p>🔴 同步失败只记 WARN + TECH 告警，<b>绝不阻断启动</b>，也不停用管道（这两张表只给 AI 查数看，
 * 不影响接收与明细）。日志只打行数与异常类名，不打 yaml 以外的任何数据。埋点表一律 {@link JdbcTemplate}，禁止 JPA。
 */
@Component
@DependsOn("analyticsBootstrap")
public class CatalogSync implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(CatalogSync.class);

    static final String ALERT_KEY_PREFIX = "analytics-catalog-sync-failed:";

    static final String UPSERT_EVENT = """
            INSERT INTO event_catalog (event, source, status, domain, description, code_ref, properties, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (event) DO UPDATE SET
                source = EXCLUDED.source,
                status = EXCLUDED.status,
                domain = EXCLUDED.domain,
                description = EXCLUDED.description,
                code_ref = EXCLUDED.code_ref,
                properties = EXCLUDED.properties,
                updated_at = now()
            WHERE (event_catalog.source, event_catalog.status, event_catalog.domain, event_catalog.description,
                   event_catalog.code_ref, event_catalog.properties)
                  IS DISTINCT FROM
                  (EXCLUDED.source, EXCLUDED.status, EXCLUDED.domain, EXCLUDED.description,
                   EXCLUDED.code_ref, EXCLUDED.properties)
            """;

    static final String DELETE_STALE_EVENTS = "DELETE FROM event_catalog WHERE NOT (event = ANY (?))";

    static final String UPSERT_METRIC = """
            INSERT INTO metric_definitions
                (code, team_ref, name, definition, sources, tables, exclusions, known_bias, sql_path, changelog, updated_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (code) DO UPDATE SET
                team_ref = EXCLUDED.team_ref,
                name = EXCLUDED.name,
                definition = EXCLUDED.definition,
                sources = EXCLUDED.sources,
                tables = EXCLUDED.tables,
                exclusions = EXCLUDED.exclusions,
                known_bias = EXCLUDED.known_bias,
                sql_path = EXCLUDED.sql_path,
                changelog = EXCLUDED.changelog,
                updated_at = now()
            WHERE (metric_definitions.team_ref, metric_definitions.name, metric_definitions.definition,
                   metric_definitions.sources, metric_definitions.tables, metric_definitions.exclusions,
                   metric_definitions.known_bias, metric_definitions.sql_path, metric_definitions.changelog)
                  IS DISTINCT FROM
                  (EXCLUDED.team_ref, EXCLUDED.name, EXCLUDED.definition, EXCLUDED.sources, EXCLUDED.tables,
                   EXCLUDED.exclusions, EXCLUDED.known_bias, EXCLUDED.sql_path, EXCLUDED.changelog)
            """;

    static final String DELETE_STALE_METRICS = "DELETE FROM metric_definitions WHERE NOT (code = ANY (?))";

    private final EventCatalog catalog;
    private final MetricDefinitions metrics;
    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public CatalogSync(EventCatalog catalog, MetricDefinitions metrics, AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(catalog, metrics, state, analyticsJdbcTemplate, analyticsTransactionTemplate, alerts, appEnv,
                Clock.systemUTC());
    }

    /** 测试用：替换时钟。 */
    CatalogSync(EventCatalog catalog, MetricDefinitions metrics, AnalyticsPipelineState state, JdbcTemplate jdbc,
            TransactionTemplate tx, OpsAlertService alerts, String appEnv, Clock clock) {
        this.catalog = catalog;
        this.metrics = metrics;
        this.state = state;
        this.jdbc = jdbc;
        this.tx = tx;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        syncOnStartup();
    }

    /**
     * 启动同步：条件不满足跳过，失败告警，绝不抛。
     *
     * @return {@code true} = 已同步
     */
    boolean syncOnStartup() {
        if (!state.isEnabled()) {
            log.info("analytics catalog sync skipped: pipeline disabled ({})", state.reason().orElse(null));
            return false;
        }
        if (!catalog.isLoaded() || !metrics.isLoaded()) {
            log.info("analytics catalog sync skipped: yaml not loaded");
            return false;
        }
        try {
            sync(catalog.events(), metrics.metrics());
            return true;
        } catch (RuntimeException | Error e) {
            log.warn("analytics catalog sync failed: {}", describe(e));
            try {
                String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
                alerts.send(OpsChannel.TECH, ALERT_KEY_PREFIX + day,
                        "【埋点清单同步失败】环境 " + appEnv + "\n"
                                + "原因：" + describe(e) + "\n"
                                + "影响：埋点接收与明细不受影响；埋点库 event_catalog / metric_definitions 停在上次同步的内容，"
                                + "AI 查数看到的事件含义可能过期。\n"
                                + "处理：检查埋点库与写入账号权限后重建后端容器（启动时会重新同步）。");
            } catch (RuntimeException ex) {
                log.warn("analytics catalog sync alert failed: {}", ex.getClass().getSimpleName());
            }
            return false;
        }
    }

    /** 一个事务内同步两张表：逐条 upsert，再删掉 yaml 里已不存在的行。 */
    void sync(List<EventDefinition> events, List<MetricDefinition> metricList) {
        List<Object[]> eventRows = new ArrayList<>(events.size());
        String[] eventNames = new String[events.size()];
        for (int i = 0; i < events.size(); i++) {
            EventDefinition e = events.get(i);
            eventNames[i] = e.name();
            eventRows.add(new Object[] {e.name(), e.source().name(), e.status().name(), e.domain(), e.description(),
                    e.codeRef(), propertiesJson(e.properties())});
        }
        List<Object[]> metricRows = new ArrayList<>(metricList.size());
        String[] codes = new String[metricList.size()];
        for (int i = 0; i < metricList.size(); i++) {
            MetricDefinition m = metricList.get(i);
            codes[i] = m.code();
            metricRows.add(new Object[] {m.code(), m.teamRef(), m.name(), m.definition(),
                    json.writeValueAsString(m.sources()), json.writeValueAsString(m.tables()), m.exclusions(),
                    m.knownBias(), m.sqlPath(), changelogJson(m.changelog())});
        }
        int[] counts = tx.execute(status -> {
            jdbc.batchUpdate(UPSERT_EVENT, eventRows);
            int deletedEvents = deleteStale(DELETE_STALE_EVENTS, eventNames);
            jdbc.batchUpdate(UPSERT_METRIC, metricRows);
            int deletedMetrics = deleteStale(DELETE_STALE_METRICS, codes);
            return new int[] {deletedEvents, deletedMetrics};
        });
        int[] deleted = counts == null ? new int[] {0, 0} : counts;
        log.info("analytics catalog synced: {} events ({} stale deleted), {} metrics ({} stale deleted)",
                events.size(), deleted[0], metricList.size(), deleted[1]);
    }

    private int deleteStale(String sql, String[] keep) {
        return jdbc.update(sql, ps -> {
            Array arr = ps.getConnection().createArrayOf("varchar", keep);
            ps.setArray(1, arr);
        });
    }

    /** 异常类名链（不含 message：可能带连接串 / 库名）。 */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        for (int depth = 0; t != null && depth < 5; depth++) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    String propertiesJson(List<PropertyDefinition> props) {
        List<Map<String, Object>> out = new ArrayList<>(props.size());
        for (PropertyDefinition p : props) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("type", p.type());
            if (p.description() != null) {
                m.put("description", p.description());
            }
            m.put("user_identifier", p.userIdentifier());
            out.add(m);
        }
        return json.writeValueAsString(out);
    }

    String changelogJson(List<ChangelogEntry> entries) {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (ChangelogEntry c : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", c.date());
            m.put("change", c.change());
            out.add(m);
        }
        return json.writeValueAsString(out);
    }
}
