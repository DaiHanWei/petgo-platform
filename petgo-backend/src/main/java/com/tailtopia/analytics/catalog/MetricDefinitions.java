package com.tailtopia.analytics.catalog;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.CatalogYaml.Errors;
import java.io.Reader;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 指标口径（analytics-pipeline Story 1.2，架构 D-9 / FR-26）：{@code analytics/metric-definitions.yaml} 的不可变快照。
 *
 * <p>加载 / 校验 / 失败处理与 {@link EventCatalog} 同一套：无效 → 停用管道（{@code CATALOG_INVALID}）+ TECH 告警，
 * <b>不抛、不阻断启动</b>。告警去重键用独立前缀，免得与事件清单同日失败时被去重吞掉。
 */
@Component
@DependsOn("analyticsBootstrap")
public class MetricDefinitions implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MetricDefinitions.class);

    public static final String DEFAULT_LOCATION = "classpath:analytics/metric-definitions.yaml";
    static final String ALERT_KEY_PREFIX = "analytics-metrics-invalid:";

    /** 与 {@code metric_definitions} 表列长度一致。 */
    static final int MAX_CODE = 32;
    static final int MAX_NAME = 128;
    static final int MAX_SQL_PATH = 255;

    /** 一条变更记录。 */
    public record ChangelogEntry(String date, String change) {
    }

    /** 一个指标。列表字段不可变。 */
    public record MetricDefinition(String code, String name, String definition, List<String> sources,
            String exclusions, String knownBias, String sqlPath, List<ChangelogEntry> changelog) {
    }

    private final String location;
    private final ResourceLoader resourceLoader;
    private final AnalyticsPipelineState state;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;

    private volatile List<MetricDefinition> metrics = List.of();
    private volatile boolean loaded;

    @Autowired
    public MetricDefinitions(
            @Value("${analytics.catalog.metric-definitions-location:" + DEFAULT_LOCATION + "}") String location,
            ResourceLoader resourceLoader,
            AnalyticsPipelineState state,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(location, resourceLoader, state, alerts, appEnv, Clock.systemUTC());
    }

    /** 测试用：替换时钟。 */
    MetricDefinitions(String location, ResourceLoader resourceLoader, AnalyticsPipelineState state,
            OpsAlertService alerts, String appEnv, Clock clock) {
        this.location = location;
        this.resourceLoader = resourceLoader;
        this.state = state;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        load();
    }

    /** 加载；失败转成停用 + 告警，绝不抛。 */
    void load() {
        try {
            List<MetricDefinition> m = build(CatalogYaml.parse(resourceLoader.getResource(location), location), location);
            this.metrics = m;
            this.loaded = true;
            log.info("analytics metric definitions loaded: {} metrics", m.size());
        } catch (CatalogInvalidException e) {
            this.metrics = List.of();
            this.loaded = false;
            CatalogYaml.reportInvalid(log, state, alerts, clock, appEnv, ALERT_KEY_PREFIX, location, e.getMessage());
        } catch (RuntimeException e) {
            this.metrics = List.of();
            this.loaded = false;
            CatalogYaml.reportInvalid(log, state, alerts, clock, appEnv, ALERT_KEY_PREFIX, location,
                    location + "：加载异常（" + e.getClass().getSimpleName() + "）");
        }
    }

    /** 全部指标（不可变，按 yaml 顺序）；未加载时为空。 */
    public List<MetricDefinition> metrics() {
        return metrics;
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ---------------------------------------------------------------- 解析 + 校验

    /** 纯函数：从 yaml 文本得到指标列表（L0 测试入口）。 */
    static List<MetricDefinition> parse(Reader reader, String fileLabel) throws CatalogInvalidException {
        return build(CatalogYaml.parse(reader, fileLabel), fileLabel);
    }

    static List<MetricDefinition> build(Object root, String fileLabel) throws CatalogInvalidException {
        Errors errors = new Errors(fileLabel);
        Map<String, Object> top = CatalogYaml.asMap(root, "(根)", errors);
        errors.throwIfAny();
        CatalogYaml.requireVersion(top, errors);
        List<?> raw = CatalogYaml.asList(top.get("metrics"), "metrics", errors);
        errors.throwIfAny();

        List<MetricDefinition> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String path = "metrics[" + i + "]";
            Map<String, Object> m = CatalogYaml.asMap(raw.get(i), path, errors);
            if (m == null) {
                continue;
            }
            String code = CatalogYaml.requiredName(m, "code", path, MAX_CODE, errors);
            if (code != null && !seen.add(code)) {
                errors.add(path + ".code", "重复：" + code);
            }
            String name = CatalogYaml.requiredString(m, "name", path, MAX_NAME, errors);
            String definition = CatalogYaml.requiredString(m, "definition", path, 0, errors);
            List<String> sources = sources(m.get("sources"), path + ".sources", errors);
            String exclusions = CatalogYaml.optionalString(m, "exclusions", path, 0, errors);
            String knownBias = CatalogYaml.optionalString(m, "known_bias", path, 0, errors);
            String sqlPath = CatalogYaml.optionalString(m, "sql_path", path, MAX_SQL_PATH, errors);
            List<ChangelogEntry> changelog = changelog(m.get("changelog"), path + ".changelog", errors);
            out.add(new MetricDefinition(code, name, definition, sources, exclusions, knownBias, sqlPath, changelog));
        }
        errors.throwIfAny();
        return List.copyOf(out);
    }

    private static List<String> sources(Object node, String path, Errors errors) {
        if (node == null) {
            return List.of();
        }
        List<?> raw = CatalogYaml.asList(node, path, errors);
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (int j = 0; j < raw.size(); j++) {
            Object v = raw.get(j);
            if (v instanceof String s && !s.isBlank()) {
                out.add(s.strip());
            } else {
                errors.add(path + "[" + j + "]", "必须是非空字符串");
            }
        }
        return List.copyOf(out);
    }

    private static List<ChangelogEntry> changelog(Object node, String path, Errors errors) {
        if (node == null) {
            return List.of();
        }
        List<?> raw = CatalogYaml.asList(node, path, errors);
        if (raw == null) {
            return List.of();
        }
        List<ChangelogEntry> out = new ArrayList<>(raw.size());
        for (int j = 0; j < raw.size(); j++) {
            String p = path + "[" + j + "]";
            Map<String, Object> m = CatalogYaml.asMap(raw.get(j), p, errors);
            if (m == null) {
                continue;
            }
            String date = CatalogYaml.requiredString(m, "date", p, 0, errors);
            String change = CatalogYaml.requiredString(m, "change", p, 0, errors);
            out.add(new ChangelogEntry(date, change));
        }
        return List.copyOf(out);
    }
}
