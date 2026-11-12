package com.tailtopia.analytics.catalog;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.CatalogYaml.Errors;
import java.io.Reader;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 事件清单（analytics-pipeline Story 1.2，架构 D-7）：{@code analytics/event-catalog.yaml} 的不可变内存快照。
 *
 * <p>启动时（{@link InitializingBean}）从 classpath 读一次。后续 story 只经下面四个方法消费，<b>签名不要改</b>：
 * <ul>
 *   <li>{@link #isRegistered}：1.4 判 {@code unknown_event}（{@code RETIRED} 也算已登记）</li>
 *   <li>{@link #allowedPropertyKeys}：1.6 明细瘦身</li>
 *   <li>{@link #userIdentifierKeys}：1.8 注销擦除</li>
 *   <li>{@link #isLoaded}</li>
 * </ul>
 *
 * <h2>🔴 yaml 无效绝不阻断启动</h2>
 * 解析 / 校验失败 → {@link AnalyticsPipelineState#disable 停用管道}（{@code CATALOG_INVALID}）+ TECH 告警，<b>不抛</b>；
 * 此时快照为空、{@link #isLoaded()} 为 false。
 *
 * <p>{@code @DependsOn("analyticsBootstrap")}：先让埋点库探活 / 迁移定出停用原因（库不在是根因），再判清单。
 */
@Component
@DependsOn("analyticsBootstrap")
public class EventCatalog implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(EventCatalog.class);

    public static final String DEFAULT_LOCATION = "classpath:analytics/event-catalog.yaml";
    /** 告警去重键前缀，后接 UTC 日期。 */
    static final String ALERT_KEY_PREFIX = "analytics-catalog-invalid:";

    /** 与 {@code event_catalog.event varchar(128)} 一致。 */
    static final int MAX_EVENT_NAME = 128;
    static final int MAX_DOMAIN = 32;
    static final int MAX_CODE_REF = 255;
    static final Pattern PROPERTY_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
    static final Set<String> PROPERTY_TYPES = Set.of("string", "int", "number", "bool", "list", "map");

    /** 事件来源。 */
    public enum Source { APP, SERVER, H5 }

    /** 登记状态。 */
    public enum Status { ACTIVE, PLANNED, RETIRED }

    /** 一个属性。 */
    public record PropertyDefinition(String name, String type, String description, boolean userIdentifier) {
    }

    /** 一个事件。{@code properties} 不可变、保持 yaml 中的顺序。 */
    public record EventDefinition(String name, Source source, Status status, String domain, String description,
            String codeRef, List<PropertyDefinition> properties) {
    }

    /** 解析后的快照。 */
    record Snapshot(List<EventDefinition> events, Map<String, Set<String>> keysByEvent, Set<String> userIdentifierKeys) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Set.of());
    }

    private final String location;
    private final ResourceLoader resourceLoader;
    private final AnalyticsPipelineState state;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile boolean loaded;

    @Autowired
    public EventCatalog(
            @Value("${analytics.catalog.event-catalog-location:" + DEFAULT_LOCATION + "}") String location,
            ResourceLoader resourceLoader,
            AnalyticsPipelineState state,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(location, resourceLoader, state, alerts, appEnv, Clock.systemUTC());
    }

    /** 测试用：替换时钟。 */
    EventCatalog(String location, ResourceLoader resourceLoader, AnalyticsPipelineState state, OpsAlertService alerts,
            String appEnv, Clock clock) {
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

    /** 加载快照；失败转成停用 + 告警，绝不抛。 */
    void load() {
        try {
            Snapshot s = build(CatalogYaml.parse(resourceLoader.getResource(location), location), location);
            this.snapshot = s;
            this.loaded = true;
            log.info("analytics event catalog loaded: {} events, {} user identifier keys",
                    s.events().size(), s.userIdentifierKeys().size());
        } catch (CatalogInvalidException e) {
            this.snapshot = Snapshot.EMPTY;
            this.loaded = false;
            CatalogYaml.reportInvalid(log, state, alerts, clock, appEnv, ALERT_KEY_PREFIX, location, e.getMessage());
        } catch (RuntimeException e) {
            this.snapshot = Snapshot.EMPTY;
            this.loaded = false;
            CatalogYaml.reportInvalid(log, state, alerts, clock, appEnv, ALERT_KEY_PREFIX, location,
                    location + "：加载异常（" + e.getClass().getSimpleName() + "）");
        }
    }

    // ---------------------------------------------------------------- 对外 API（后续 story 依赖，不要改签名）

    /** 事件是否已登记（任何 status，含 {@code RETIRED} / {@code PLANNED}）。未加载时恒 false。 */
    public boolean isRegistered(String event) {
        return event != null && snapshot.keysByEvent().containsKey(event);
    }

    /** 该事件登记过的属性键（不可变）；未登记 / 未加载时为空集。 */
    public Set<String> allowedPropertyKeys(String event) {
        if (event == null) {
            return Set.of();
        }
        Set<String> keys = snapshot.keysByEvent().get(event);
        return keys == null ? Set.of() : keys;
    }

    /** 全清单所有 {@code user_identifier: true} 的属性键的并集（不可变）。注销擦除用。 */
    public Set<String> userIdentifierKeys() {
        return snapshot.userIdentifierKeys();
    }

    /** yaml 是否已成功加载。 */
    public boolean isLoaded() {
        return loaded;
    }

    /** 全部事件（不可变，按 yaml 顺序）。供同步进库与测试。 */
    public List<EventDefinition> events() {
        return snapshot.events();
    }

    // ---------------------------------------------------------------- 解析 + 校验

    /** 纯函数：从 yaml 文本得到快照（L0 测试入口）。 */
    static Snapshot parse(Reader reader, String fileLabel) throws CatalogInvalidException {
        return build(CatalogYaml.parse(reader, fileLabel), fileLabel);
    }

    static Snapshot build(Object root, String fileLabel) throws CatalogInvalidException {
        Errors errors = new Errors(fileLabel);
        Map<String, Object> top = CatalogYaml.asMap(root, "(根)", errors);
        errors.throwIfAny();
        CatalogYaml.requireVersion(top, errors);
        List<?> rawEvents = CatalogYaml.asList(top.get("events"), "events", errors);
        errors.throwIfAny();

        List<EventDefinition> events = new ArrayList<>(rawEvents.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rawEvents.size(); i++) {
            String path = "events[" + i + "]";
            Map<String, Object> m = CatalogYaml.asMap(rawEvents.get(i), path, errors);
            if (m == null) {
                continue;
            }
            String name = CatalogYaml.requiredName(m, "name", path, MAX_EVENT_NAME, errors);
            if (name != null && !seen.add(name)) {
                errors.add(path + ".name", "重复：" + name);
            }
            Source source = enumValue(Source.class, m, "source", path, errors);
            Status status = enumValue(Status.class, m, "status", path, errors);
            String domain = CatalogYaml.optionalString(m, "domain", path, MAX_DOMAIN, errors);
            String description = CatalogYaml.requiredString(m, "description", path, 0, errors);
            String codeRef = CatalogYaml.optionalString(m, "code_ref", path, MAX_CODE_REF, errors);
            List<PropertyDefinition> props = properties(m.get("properties"), path + ".properties", errors);
            events.add(new EventDefinition(name, source, status, domain, description, codeRef, props));
        }
        errors.throwIfAny();

        Map<String, Set<String>> keysByEvent = new LinkedHashMap<>();
        Set<String> idKeys = new LinkedHashSet<>();
        for (EventDefinition e : events) {
            Set<String> keys = new LinkedHashSet<>();
            for (PropertyDefinition p : e.properties()) {
                keys.add(p.name());
                if (p.userIdentifier()) {
                    idKeys.add(p.name());
                }
            }
            keysByEvent.put(e.name(), Collections.unmodifiableSet(keys));
        }
        return new Snapshot(List.copyOf(events), Collections.unmodifiableMap(keysByEvent),
                Collections.unmodifiableSet(idKeys));
    }

    private static List<PropertyDefinition> properties(Object node, String path, Errors errors) {
        if (node == null) {
            return List.of();
        }
        List<?> raw = CatalogYaml.asList(node, path, errors);
        if (raw == null) {
            return List.of();
        }
        List<PropertyDefinition> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (int j = 0; j < raw.size(); j++) {
            String p = path + "[" + j + "]";
            Map<String, Object> m = CatalogYaml.asMap(raw.get(j), p, errors);
            if (m == null) {
                continue;
            }
            String name = CatalogYaml.requiredName(m, "name", p, 0, errors);
            if (name != null) {
                if (!PROPERTY_NAME.matcher(name).matches()) {
                    errors.add(p + ".name", "不符合 ^[a-z][a-z0-9_]*$：" + name);
                } else if (!seen.add(name)) {
                    errors.add(p + ".name", "重复：" + name);
                }
            }
            String type = CatalogYaml.requiredString(m, "type", p, 0, errors);
            if (type != null && !PROPERTY_TYPES.contains(type)) {
                errors.add(p + ".type", "取值非法（string / int / number / bool / list / map）：" + type);
            }
            String description = CatalogYaml.optionalString(m, "description", p, 0, errors);
            boolean uid = CatalogYaml.optionalBool(m, "user_identifier", p, false, errors);
            out.add(new PropertyDefinition(name, type, description, uid));
        }
        return List.copyOf(out);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Map<String, Object> m, String key, String path,
            Errors errors) {
        String v = CatalogYaml.requiredString(m, key, path, 0, errors);
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            errors.add(path + "." + key, "取值非法：" + v);
            return null;
        }
    }
}
