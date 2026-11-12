package com.tailtopia.analytics.rollup;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.rollup.BusinessPlacementReader.Campaign;
import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import com.tailtopia.analytics.store.EventTimeCorrector;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 指标族 {@code push}（analytics-pipeline Story 4.4 T5，S5 / FR-23，D-12 / D-13）：推送发送数、打开次数、打开人数，
 * 写 {@code agg_daily_push} / {@code agg_weekly_push} / {@code agg_monthly_push}。SQL 见 {@code analytics/rollup/push.sql}。
 *
 * <ul>
 *   <li>运营推送（{@code push_type = OPS_ANNOUNCEMENT}）按 {@code campaign_token} 一条一行，带业务库
 *       {@code ops_notification_campaigns} 的 {@code planned_count} / {@code recipient_count}（接收 = 写入通知中心人数，近似）与
 *       是否推广 Toko（{@link PlacementTokoRule#opsTarget}）；{@code campaign_opened_persons_cum} = 发送日起
 *       {@link PushRollupProperties#pushCampaignDays()}（默认 14）天内的去重打开人数。</li>
 *   <li>券过期推送（{@code COUPON_EXPIRING}）的发送数取服务端事件 {@code toko_coupon_expiry_push_sent}；其他系统推送没有发送记录，
 *       只记打开（A13 勘察结论见 metric-definitions.yaml「S5-推送」）。</li>
 *   <li>{@link #recomputeDays()} = 3（D-6）。累计打开人数每天在统计日给「发送后 N 天内」的活动都补一行，所以 {@code as_of} 当天的行
 *       就是截至昨天的累计值；超过 N 天的晚打开不计入累计（已知偏差）。</li>
 *   <li>周 / 月：打开人数周期内重新从明细去重（D-12），发送数 / 打开次数周期内求和；周期首日早于汇总下限的不算。</li>
 * </ul>
 *
 * <p>业务库窄查询在 {@link #prepareDay}（埋点库事务之外），一次运行只读一次活动表；单元事务内建 {@code tmp_campaigns}
 * （{@code ON COMMIT DROP}）后执行 SQL。日志只打条数、日期、耗时。
 */
public class PushFamily implements RollupFamily {

    private static final Logger log = LoggerFactory.getLogger(PushFamily.class);

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "push";
    /** 配置非法告警去重键。 */
    static final String CONFIG_ALERT_KEY = "analytics-rollup-push-config-invalid";

    static final String CREATE_CAMPAIGNS_SQL = "CREATE TEMP TABLE tmp_campaigns (campaign_token varchar(32) PRIMARY KEY, "
            + "sent_date date NOT NULL, planned_count int, recipient_count int, promotes_toko boolean) ON COMMIT DROP";
    static final String FILL_CAMPAIGNS_SQL = "INSERT INTO tmp_campaigns (campaign_token, sent_date, planned_count, "
            + "recipient_count, promotes_toko) SELECT t, s::date, pc, rc, pt "
            + "FROM unnest(?::text[], ?::text[], ?::int[], ?::int[], ?::boolean[]) AS x(t, s, pc, rc, pt)";

    /** 运营推送活动（已换算发送日）。 */
    record CampaignRow(String token, LocalDate sentDate, Integer plannedCount, Integer recipientCount,
            boolean promotesToko) {
    }

    private final BusinessPlacementReader reader;
    private final PushRollupProperties props;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final List<String> compute;
    private final List<String> daily;
    private final List<String> weekly;
    private final List<String> monthly;
    /** 本次运行读到的活动（按运行上下文缓存：一次运行只读一次业务库）。 */
    private RollupContext campaignsFor;
    private List<CampaignRow> campaigns = List.of();
    /** prepareDay 写、computeDay 读。 */
    private LocalDate preparedDay;

    public PushFamily(BusinessPlacementReader reader, PushRollupProperties props, OpsAlertService alerts,
            String appEnv) {
        this.reader = reader;
        this.props = props;
        this.alerts = alerts;
        this.appEnv = appEnv;
        Map<String, List<String>> sections = RollupSqlSections.load(NAME);
        String where = SqlRollupFamily.resourcePath(NAME);
        this.compute = RollupSqlSections.require(sections, "compute", where);
        this.daily = RollupSqlSections.require(sections, "daily", where);
        this.weekly = RollupSqlSections.require(sections, "weekly", where);
        this.monthly = RollupSqlSections.require(sections, "monthly", where);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean hasPeriods() {
        return true;
    }

    /** 配置非法 → 应用就绪时 TECH 告警一次（后端照常运行，只停用累计打开人数）。绝不抛。 */
    @EventListener(ApplicationReadyEvent.class)
    public void checkConfigOnStartup() {
        if (props.invalidReason() == null || alerts == null) {
            return;
        }
        try {
            alerts.send(OpsChannel.TECH, CONFIG_ALERT_KEY, "【埋点配置非法】环境 " + appEnv + "\n"
                    + "问题：" + props.invalidReason() + "\n"
                    + "影响：后端照常运行；运营推送累计打开人数（agg_daily_push.campaign_opened_persons_cum）停算、写空，"
                    + "日 / 周 / 月打开数照常。\n"
                    + "处理：改对 env ANALYTICS_ROLLUP_PUSH_CAMPAIGN_DAYS（1–92 的整数，默认 14）后重建容器，重算窗口内的行自动恢复。");
        } catch (RuntimeException e) {
            log.warn("analytics rollup push config alert failed: {}", e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- 日

    @Override
    public void prepareDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        preparedDay = null;
        if (campaignsFor != ctx) {
            long t0 = System.nanoTime();
            campaigns = toRows(reader.campaigns());
            campaignsFor = ctx;
            log.info("analytics rollup push campaigns loaded: family={} campaigns={} ms={}", NAME, campaigns.size(),
                    (System.nanoTime() - t0) / 1_000_000);
        }
        preparedDay = statDate;
    }

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        if (!statDate.equals(preparedDay) || campaignsFor != ctx) {
            throw new IllegalStateException("push: prepareDay 未在本单元之前执行（" + statDate + "）");
        }
        preparedDay = null;
        fillCampaigns(ctx.jdbcTemplate(), campaigns);
        MapSqlParameterSource params = PlacementFamily.periodParams(statDate, statDate, asOf, ctx)
                .addValue("campaign_days", props.pushCampaignDays());
        run(ctx, compute, params);
        run(ctx, daily, params);
    }

    // ---------------------------------------------------------------- 周 / 月（只用埋点库，无需准备业务数据）

    @Override
    public void computePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, RollupContext ctx) {
        for (Period period : PlacementFamily.computablePeriods(windowStart, windowEnd, asOf,
                ctx.properties().retentionDays())) {
            MapSqlParameterSource params = PlacementFamily.periodParams(period.start(), period.end(), asOf, ctx);
            run(ctx, compute, params);
            run(ctx, period.kind() == Kind.WEEK ? weekly : monthly, params);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 业务库活动 → 发送日（WIB）+ 是否推广 Toko；发送时间为空（理论上不会）或 token 超过 32 字符的跳过。 */
    static List<CampaignRow> toRows(List<Campaign> in) {
        List<CampaignRow> out = new ArrayList<>(in.size());
        for (Campaign c : in) {
            if (c.sentAt() == null || c.token() == null || c.token().isEmpty() || c.token().length() > 32) {
                continue;
            }
            out.add(new CampaignRow(c.token(), EventTimeCorrector.wibDate(c.sentAt()), c.plannedCount(),
                    c.recipientCount(), PlacementTokoRule.opsTarget(c.targetType(), c.targetValue())));
        }
        return List.copyOf(out);
    }

    static void fillCampaigns(JdbcTemplate jdbc, List<CampaignRow> rows) {
        jdbc.execute(CREATE_CAMPAIGNS_SQL);
        if (rows.isEmpty()) {
            return;
        }
        String[] tokens = rows.stream().map(CampaignRow::token).toArray(String[]::new);
        String[] dates = rows.stream().map(r -> r.sentDate().toString()).toArray(String[]::new);
        Integer[] planned = rows.stream().map(CampaignRow::plannedCount).toArray(Integer[]::new);
        Integer[] recipients = rows.stream().map(CampaignRow::recipientCount).toArray(Integer[]::new);
        Boolean[] toko = rows.stream().map(CampaignRow::promotesToko).toArray(Boolean[]::new);
        jdbc.update(FILL_CAMPAIGNS_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", tokens));
            ps.setArray(2, ps.getConnection().createArrayOf("text", dates));
            ps.setArray(3, ps.getConnection().createArrayOf("int4", planned));
            ps.setArray(4, ps.getConnection().createArrayOf("int4", recipients));
            ps.setArray(5, ps.getConnection().createArrayOf("boolean", toko));
        });
    }

    private static void run(RollupContext ctx, List<String> statements, MapSqlParameterSource params) {
        for (String sql : statements) {
            ctx.jdbc().update(sql, params);
        }
    }

    /** 测试用：各段语句。 */
    List<String> section(String name) {
        return switch (name) {
            case "compute" -> compute;
            case "daily" -> daily;
            case "weekly" -> weekly;
            case "monthly" -> monthly;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
