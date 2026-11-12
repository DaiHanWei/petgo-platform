package com.tailtopia.analytics.catalog;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import com.tailtopia.analytics.DauSource;
import java.io.StringReader;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 日活来源切换日期守门（analytics-pipeline Story 4.7 AC3 / AC9，FR-24）。
 *
 * <p>{@code analytics.dau-source = SELF} 时，{@code metric-definitions.yaml} 的 S1 条目必须填 {@code dau_source_switch}
 * （切换日期 {@code YYYY-MM-DD}，并在变更记录写原因）——否则 AI 查数无从知道哪天起 S1 / 比率分母换了口径。
 * 应用就绪时（口径加载与同步都在 bean 初始化阶段完成，早于就绪事件）检查：SELF 且字段为空 / 不是合法日期 →
 * WARN + TECH 告警一次（去重键按 WIB 日，同一天多次重启只发一次）。
 *
 * <p>🔴 <b>绝不阻断启动、绝不抛</b>（架构「故障隔离」）：yaml 读不到也只按「字段为空」处理（口径文件本身的错误由
 * {@link MetricDefinitions} 告警）。日志只打来源与键名，不打其他配置值。
 */
@Component
public class DauSourceSwitchGuard {

    private static final Logger log = LoggerFactory.getLogger(DauSourceSwitchGuard.class);

    /** S1 条目里的切换日期字段名。 */
    public static final String FIELD = "dau_source_switch";
    static final String S1 = "S1";
    static final String ALERT_KEY_PREFIX = "analytics-dau-source-switch-missing:";
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final String location;
    private final ResourceLoader resourceLoader;
    private final AnalyticsDauSourceProperties dauSource;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;

    @Autowired
    public DauSourceSwitchGuard(
            @Value("${analytics.catalog.metric-definitions-location:" + MetricDefinitions.DEFAULT_LOCATION + "}")
            String location,
            ResourceLoader resourceLoader,
            AnalyticsDauSourceProperties dauSource,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(location, resourceLoader, dauSource, alerts, appEnv, Clock.systemUTC());
    }

    /** 测试用：替换时钟。 */
    DauSourceSwitchGuard(String location, ResourceLoader resourceLoader, AnalyticsDauSourceProperties dauSource,
            OpsAlertService alerts, String appEnv, Clock clock) {
        this.location = location;
        this.resourceLoader = resourceLoader;
        this.dauSource = dauSource;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 应用就绪时检查一次。绝不抛。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        check();
    }

    /** 检查；返回是否告警（测试用；监听方法不能有返回值，否则 Spring 会把它当新事件发布）。绝不抛。 */
    boolean check() {
        try {
            DauSource source = dauSource.dauSource();
            if (source != DauSource.SELF) {
                return false;
            }
            LocalDate switchDate = readSwitchDate();
            if (!missingSwitchDate(source, switchDate)) {
                return false;
            }
            log.warn("analytics.dau-source=SELF but metric S1 has no {} (YYYY-MM-DD) in metric-definitions.yaml", FIELD);
            try {
                alerts.send(OpsChannel.TECH, ALERT_KEY_PREFIX + LocalDate.now(clock.withZone(WIB)),
                        "【埋点口径缺切换日期】环境 " + appEnv + "\n"
                        + "问题：analytics.dau-source = SELF，但 metric-definitions.yaml 的 S1 条目没有 " + FIELD
                        + "（或不是 YYYY-MM-DD）。\n"
                        + "影响：后端照常运行，S1 与比率分母已按自建日活计算；但口径里查不到切换日期，AI 查数无法区分前后口径。\n"
                        + "处理：在 S1 条目填 " + FIELD + ": \"YYYY-MM-DD\" 并在 changelog 写原因，随下次发版生效"
                        + "（见 runbook-analytics-pipeline）。");
            } catch (RuntimeException e) {
                log.warn("analytics dau-source switch alert failed: {}", e.getClass().getSimpleName());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("analytics dau-source switch check failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 纯判断（AC9）：SELF 且没有切换日期 → 需告警；GA4 一律不需要。 */
    static boolean missingSwitchDate(DauSource source, LocalDate switchDate) {
        return source == DauSource.SELF && switchDate == null;
    }

    /** 读口径文件里 S1 的切换日期；文件读不到 / 解析失败 → null（按缺失处理）。 */
    LocalDate readSwitchDate() {
        try {
            return switchDate(CatalogYaml.parse(resourceLoader.getResource(location), location));
        } catch (CatalogInvalidException e) {
            return null;
        }
    }

    /** 测试入口：从 yaml 文本取 S1 的切换日期。 */
    static LocalDate switchDate(String yaml) throws CatalogInvalidException {
        return switchDate(CatalogYaml.parse(new StringReader(yaml), "test"));
    }

    /**
     * 从已解析的口径 yaml 取 S1 条目的 {@value #FIELD}：缺失 / null / 空串 / 不是合法 {@code YYYY-MM-DD} → null。
     * 未加引号的日期被 SnakeYAML 解析成 {@link Date}（UTC 零点），也认。
     */
    static LocalDate switchDate(Object root) {
        if (!(root instanceof Map<?, ?> top) || !(top.get("metrics") instanceof List<?> metrics)) {
            return null;
        }
        for (Object m : metrics) {
            if (m instanceof Map<?, ?> entry && S1.equals(String.valueOf(entry.get("code")))) {
                return toDate(entry.get(FIELD));
            }
        }
        return null;
    }

    private static LocalDate toDate(Object v) {
        if (v instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return LocalDate.parse(s.strip());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
