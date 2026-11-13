package com.tailtopia.analytics.config;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.erasure.ErasureJob;
import com.tailtopia.analytics.ingest.IngestProperties;
import com.tailtopia.analytics.rollup.AnalyticsRollupProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 埋点配置非法的就绪告警（analytics-pipeline Story 4.9 AC8 / AC10 / AC11）：应用就绪时汇总各处的非法配置（只含键名），
 * 经 TECH 渠道发一条「【埋点配置非法】…」，去重键 {@code analytics-config-invalid:<UTC 日>}。没有问题不发。
 *
 * <p>来源：{@link AnalyticsRollupProperties}（非法 = 汇总停用）、{@link AnalyticsStorageProperties}（非法 = 按水位停收停用）、
 * {@link IngestProperties}（非法键回退默认）、{@link ErasureJob}（擦除超时回退默认）、{@link AnalyticsConfigProblems}
 * （数据源池参数、请求查询超时、调度 cron / 时长）。GA4 快照 / 日活来源 / 推送天数沿用 4-2 / 4-4 各自已有的就绪告警，不在这里重复。
 *
 * <p>🔴 埋点配置写错不能让业务后端起不来，也不能静默。告警在事务外调用，外层 {@code catch RuntimeException}，绝不抛。
 */
@Component
public class AnalyticsConfigAlert {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsConfigAlert.class);

    static final String ALERT_PREFIX = "analytics-config-invalid:";

    private final ObjectProvider<AnalyticsRollupProperties> rollup;
    private final ObjectProvider<AnalyticsStorageProperties> storage;
    private final ObjectProvider<IngestProperties> ingest;
    private final ObjectProvider<ErasureJob> erasure;
    private final AnalyticsConfigProblems problems;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;

    @Autowired
    public AnalyticsConfigAlert(ObjectProvider<AnalyticsRollupProperties> rollup,
            ObjectProvider<AnalyticsStorageProperties> storage, ObjectProvider<IngestProperties> ingest,
            ObjectProvider<ErasureJob> erasure, AnalyticsConfigProblems problems, OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(rollup, storage, ingest, erasure, problems, alerts, appEnv, Clock.systemUTC());
    }

    AnalyticsConfigAlert(ObjectProvider<AnalyticsRollupProperties> rollup,
            ObjectProvider<AnalyticsStorageProperties> storage, ObjectProvider<IngestProperties> ingest,
            ObjectProvider<ErasureJob> erasure, AnalyticsConfigProblems problems, OpsAlertService alerts,
            String appEnv, Clock clock) {
        this.rollup = rollup;
        this.storage = storage;
        this.ingest = ingest;
        this.erasure = erasure;
        this.problems = problems;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 当前全部问题（只含键名与影响）。 */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        rollup.ifAvailable(p -> {
            if (p.invalidReason() != null) {
                out.add(p.invalidReason() + "（指标汇总与手动重跑已停用）");
            }
        });
        storage.ifAvailable(p -> {
            if (p.invalidReason() != null) {
                out.add(p.invalidReason() + (p.breakerEnabled() ? "（已回退默认值）"
                        : "（已回退默认值；已停用按水位停收，接收照常）"));
            }
        });
        ingest.ifAvailable(p -> {
            if (p.invalidReason() != null) {
                out.add(p.invalidReason() + "（已回退默认值，接收照常）");
            }
        });
        erasure.ifAvailable(j -> {
            if (j.invalidReason() != null) {
                out.add(j.invalidReason());
            }
        });
        out.addAll(problems.all());
        return out;
    }

    /** 应用就绪时发一次告警。绝不抛。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            List<String> all = problems();
            if (all.isEmpty()) {
                return;
            }
            log.warn("analytics config invalid: count={}", all.size());
            LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            alerts.send(OpsChannel.TECH, ALERT_PREFIX + today, "【埋点配置非法】环境 " + appEnv + "\n"
                    + "问题：" + String.join("；", all) + "\n"
                    + "影响：后端照常运行，只停用 / 回退上面列出的埋点功能。\n"
                    + "处理：改正 env 后重建容器（docker restart 不重读 env），见 runbook-analytics-pipeline「配置表」。");
        } catch (RuntimeException e) {
            log.warn("analytics config alert failed: {}", e.getClass().getSimpleName());
        }
    }
}
