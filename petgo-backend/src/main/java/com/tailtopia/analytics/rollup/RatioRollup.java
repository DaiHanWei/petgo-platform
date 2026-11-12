package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import com.tailtopia.analytics.DauSource;
import com.tailtopia.analytics.store.EventTimeCorrector;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 指标族 {@code ratio}（analytics-pipeline Story 4.7，S4 / 产品 #3 / 内容 #1，FR-22 / FR-24 / D-12 / D-13）：
 * 以日活为分母的比率，写 {@code agg_daily_ratio}（每 WIB 日一行，只有日表；月比率 = 当月每日比率的算术平均，由日表现算）。
 *
 * <h2>流程（每个 WIB 日一个单元）</h2>
 * <ol>
 *   <li>{@link #prepareDay}（埋点事务<b>之外</b>）：经 {@link PayerCountReader} 窄查询业务库
 *       {@code [当天 0 点 WIB, 次日 0 点 WIB)} 的各类付费人 {@code user_id}，在内存里去重成人数（产品 #3 = 六类并集）；
 *       用户 id 不进埋点库、不打日志。</li>
 *   <li>{@link #computeDay}（单元事务内）：{@code ratio.sql} 的 {@code denominator} 段按 {@code analytics.dau-source}
 *       取分母（GA4 = {@code ga4_daily_snapshot.active_users_all}；SELF = {@code agg_daily_activity} 的 all 行
 *       {@code dau_person}），本类算比率与回访用户，{@code upsert} 段写入。</li>
 * </ol>
 *
 * <h2>分母缺失（AC7）</h2>
 * 分母取不到（GA4 当天未拉到、stag 无 GA4 凭证）→ {@code dau}、{@code s4_ratio}、{@code p3_ratio}、{@code returning_users} 为 NULL，
 * 分子照常写；不用 0 顶替、不报错。分母为 0 → 两个比率 NULL（回访用户 = 0 − 新用户）。每次运行重算最近 3 天（D-6，与 GA4 回拉 3 天一致），
 * GA4 补上后下一次运行自动填上。
 *
 * <h2>顺序</h2>
 * {@link #dependsOn()} {@code activity}：SELF 分母读 activity 当轮结果，activity 失败 / 跳过时本指标族跳过。
 * GA4 快照任务（WIB 02:30）早于 {@code RollupJob}（WIB 03:00）；快照失败的日子分母为 NULL，由之后的补拉 + 3 天回补填上。
 */
public class RatioRollup implements RollupFamily {

    private static final Logger log = LoggerFactory.getLogger(RatioRollup.class);

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "ratio";
    /** 每次重算 {@code [Y − 2, Y]}（D-6）。 */
    public static final int RECOMPUTE_DAYS = 3;
    /** 比率小数位（{@code numeric(9,6)}）。 */
    static final int RATIO_SCALE = 6;
    /** {@code numeric(9,6)} 能存的最大值；比率超出时封顶（防写入溢出卡死进度）。 */
    static final BigDecimal RATIO_MAX = new BigDecimal("999.999999");

    /** 一天的业务库分子人数。 */
    record Numerators(int tokoBuyers, int nontokoPayers, int ktp, int tailsonality, int passport, int petSlot,
            int aiUnlock, int vetConsult, int gift, int topup, int newUsers) {
    }

    /** 由分母派生的列。 */
    record Derived(BigDecimal s4Ratio, BigDecimal p3Ratio, Integer returningUsers) {
    }

    private record Prepared(LocalDate statDate, Numerators numerators) {
    }

    private final PayerCountReader reader;
    private final AnalyticsDauSourceProperties dauSource;
    private final List<String> denominator;
    private final List<String> upsert;
    /** prepare 写、compute 读（{@link RollupService} 在 JVM 运行锁下串行调用）。 */
    private Prepared pending;

    public RatioRollup(PayerCountReader reader, AnalyticsDauSourceProperties dauSource) {
        this.reader = reader;
        this.dauSource = dauSource;
        Map<String, List<String>> sections = RollupSqlSections.load(NAME);
        String where = SqlRollupFamily.resourcePath(NAME);
        this.denominator = RollupSqlSections.require(sections, "denominator", where);
        this.upsert = RollupSqlSections.require(sections, "upsert", where);
        if (denominator.size() != 1 || upsert.size() != 1) {
            throw new IllegalStateException(where + "：denominator / upsert 段各应只有一条语句");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int recomputeDays() {
        return RECOMPUTE_DAYS;
    }

    @Override
    public Set<String> dependsOn() {
        return Set.of("activity");
    }

    // ---------------------------------------------------------------- 准备（事务外，业务库）

    @Override
    public void prepareDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        pending = null;
        long t0 = System.nanoTime();
        Instant from = wibStart(statDate);
        Instant to = wibStart(statDate.plusDays(1));
        Numerators n = numerators(
                () -> reader.tokoBuyers(from, to),
                List.of(() -> reader.ktpPayers(from, to), () -> reader.tailsonalityPayers(from, to),
                        () -> reader.passportPayers(from, to), () -> reader.petSlotPayers(from, to),
                        () -> reader.aiUnlockPayers(from, to), () -> reader.vetConsultPayers(from, to)),
                () -> reader.giftSenders(from, to), () -> reader.topupPayers(from, to), reader.newUsers(from, to));
        pending = new Prepared(statDate, n);
        log.info("analytics rollup ratio prepared: family={} date={} tokoBuyers={} nontokoPayers={} newUsers={} ms={}",
                NAME, statDate, n.tokoBuyers(), n.nontokoPayers(), n.newUsers(), (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * 纯计算：各类付费人集合 → 人数。{@code nonToko} 依次为 KTP、Tailsonality、护照、名额、AI 解锁、兽医问诊六类，
     * 产品 #3 分子 = 六类的去重并集（同一人跨类只算一次）；送礼与充值只作参照、不进并集（Dai 2026-11-12 拍板）。
     */
    static Numerators numerators(Supplier<Set<Long>> toko, List<Supplier<Set<Long>>> nonToko,
            Supplier<Set<Long>> gift, Supplier<Set<Long>> topup, long newUsers) {
        if (nonToko.size() != 6) {
            throw new IllegalArgumentException("非 Toko 付费应为六类");
        }
        int[] counts = new int[6];
        Set<Long> union = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            Set<Long> s = nonToko.get(i).get();
            counts[i] = s.size();
            union.addAll(s);
        }
        return new Numerators(toko.get().size(), union.size(), counts[0], counts[1], counts[2], counts[3], counts[4],
                counts[5], gift.get().size(), topup.get().size(), Math.toIntExact(newUsers));
    }

    // ---------------------------------------------------------------- 计算（单元事务内，埋点库）

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        Prepared p = pending;
        pending = null;
        if (p == null || !p.statDate().equals(statDate)) {
            throw new IllegalStateException("ratio: prepare 未在本单元之前执行（" + statDate + "）");
        }
        DauSource source = dauSource.dauSource();
        MapSqlParameterSource denominatorParams = new MapSqlParameterSource()
                .addValue("stat_date", statDate)
                .addValue("dau_source", source.name());
        Long dauRaw = ctx.jdbc().queryForObject(denominator.get(0), denominatorParams, Long.class);
        Integer dau = dauRaw == null ? null : Math.toIntExact(dauRaw);
        if (dau == null) {
            log.info("analytics rollup ratio denominator missing: family={} date={} source={}", NAME, statDate, source);
        }
        MapSqlParameterSource params = upsertParams(statDate, source, dau, p.numerators(), ctx.runAt());
        ctx.jdbc().update(upsert.get(0), params);
    }

    /** upsert 段参数（纯函数）：分子、分母与派生列；可为 NULL 的列带 SQL 类型。 */
    static MapSqlParameterSource upsertParams(LocalDate statDate, DauSource source, Integer dau, Numerators n,
            Instant runAt) {
        Derived d = derive(dau, n.tokoBuyers(), n.nontokoPayers(), n.newUsers());
        return new MapSqlParameterSource()
                .addValue("stat_date", statDate)
                .addValue("dau_source", source.name())
                .addValue("computed_at", OffsetDateTime.ofInstant(runAt, ZoneOffset.UTC))
                .addValue("dau", dau, Types.INTEGER)
                .addValue("toko_buyers", n.tokoBuyers(), Types.INTEGER)
                .addValue("s4_ratio", d.s4Ratio(), Types.NUMERIC)
                .addValue("nontoko_payers", n.nontokoPayers(), Types.INTEGER)
                .addValue("p3_ratio", d.p3Ratio(), Types.NUMERIC)
                .addValue("payers_ktp", n.ktp(), Types.INTEGER)
                .addValue("payers_tailsonality", n.tailsonality(), Types.INTEGER)
                .addValue("payers_passport", n.passport(), Types.INTEGER)
                .addValue("payers_pet_slot", n.petSlot(), Types.INTEGER)
                .addValue("payers_ai_unlock", n.aiUnlock(), Types.INTEGER)
                .addValue("payers_vet_consult", n.vetConsult(), Types.INTEGER)
                .addValue("payers_gift", n.gift(), Types.INTEGER)
                .addValue("payers_topup", n.topup(), Types.INTEGER)
                .addValue("new_users", n.newUsers(), Types.INTEGER)
                .addValue("returning_users", d.returningUsers(), Types.INTEGER);
    }

    /**
     * 纯计算（AC4 / AC7）：分母 NULL → 两个比率与回访用户全为 NULL；分母 0 → 两个比率 NULL、回访 = −新用户；
     * 否则比率 = 分子 ÷ 分母（六位小数四舍五入），回访 = 分母 − 新用户（可能为负，不截断）。
     */
    static Derived derive(Integer dau, int tokoBuyers, int nontokoPayers, int newUsers) {
        if (dau == null) {
            return new Derived(null, null, null);
        }
        return new Derived(ratio(tokoBuyers, dau), ratio(nontokoPayers, dau), dau - newUsers);
    }

    /**
     * 分子 ÷ 分母，六位小数四舍五入；分母为 NULL 或 ≤ 0 → NULL；超过 {@code numeric(9,6)} 上限时封顶 {@link #RATIO_MAX}
     * （代码复审 2026-11-12 R1：分子读业务库、SELF 分母来自埋点，埋点当天大面积缺数时分母可能极小，比值 ≥ 1000 会让
     * upsert 报 numeric field overflow、整族单元失败、进度卡在这一天；同 4.6 R1 的封顶处理）。
     */
    static BigDecimal ratio(int numerator, Integer dau) {
        if (dau == null || dau <= 0) {
            return null;
        }
        BigDecimal r = BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(dau), RATIO_SCALE, RoundingMode.HALF_UP);
        if (r.compareTo(RATIO_MAX) > 0) {
            log.warn("analytics rollup ratio capped: family={} max={}", NAME, RATIO_MAX);
            return RATIO_MAX;
        }
        return r;
    }

    /** WIB 日期 {@code d} 的 0 点（UTC 时刻）。 */
    static Instant wibStart(LocalDate d) {
        return d.atStartOfDay(EventTimeCorrector.WIB).toInstant();
    }

    /** 各段语句（测试用）。 */
    List<String> section(String name) {
        return switch (name) {
            case "denominator" -> denominator;
            case "upsert" -> upsert;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
