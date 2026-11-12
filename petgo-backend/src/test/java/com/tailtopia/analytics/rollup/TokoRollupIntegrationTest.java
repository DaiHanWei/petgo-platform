package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.rollup.RollupService.Outcome;
import com.tailtopia.analytics.store.DetailPartitions;
import com.tailtopia.analytics.store.EventTimeCorrector;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：指标族 {@code toko}（analytics-pipeline Story 4.5 AC1–AC5、AC8、AC9 / T6），真业务库 + 埋点测试库
 * {@code petgo_analytics_test}（前置同 {@code RollupActivityInstallIntegrationTest}）。
 *
 * <ul>
 *   <li>隔离：Toko 汇总表每个日期 / 周 / 月只有一行（全库共用），按「先跑一次取基线 → 造数 → 再跑」取增量断言；
 *       {@code orders_paid} 另与直接查业务库的绝对值比对（AC9）。</li>
 *   <li>统计日 X 取 WIB 今天往前约 6 周的周一（X 到 X + 8 在同一个月）；{@code as_of} = WIB 昨天（X 的 7 天窗口已关闭）。</li>
 *   <li>明细 / 关联按 {@code message_id} 前缀清理；业务库订单按 token 前缀清理。</li>
 * </ul>
 */
class TokoRollupIntegrationTest extends ApiIntegrationTest {

    private static final String[] METRICS = {"toko_viewers", "funnel_s1_toko_page", "funnel_s2_product_shown",
        "funnel_s3_detail_viewed", "funnel_s4_buy_or_cart", "funnel_s5_order_submitted", "funnel_s6_pay_tapped",
        "funnel_s7_paid", "orders_submitted", "orders_paid", "orders_created_biz", "atc_users", "atc_converted_users"};

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;
    @Autowired
    private JdbcTemplate businessJdbc;

    private RollupService service;
    private String prefix;
    private String tok;
    private LocalDate x;
    private LocalDate asOf;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        BusinessPlacementReader reader = new BusinessPlacementReader(businessJdbc);
        OpsAlertService alerts = mock(OpsAlertService.class);
        service = new RollupService(state, analyticsJdbc, analyticsTx, businessJdbc,
                new RollupProgressRepository(analyticsJdbc), List.of(new TokoFamily(reader)),
                AnalyticsRollupProperties.defaults(), alerts, "test", Clock.systemUTC());
        long n = SEQ.incrementAndGet();
        prefix = "it-toko-" + n + "-";
        tok = "itk" + Long.toString(n % 1_000_000_000_000L, 36);
        LocalDate todayWib = EventTimeCorrector.wibDate(Instant.now());
        asOf = todayWib.minusDays(1);
        x = todayWib.minusDays(42).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (x.getDayOfMonth() > 20) {
            x = x.plusWeeks(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        partitions(x.minusDays(1), x.plusDays(16));
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM identity_links WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        businessJdbc.update("DELETE FROM shop_orders WHERE public_token LIKE ?", tok + "%");
    }

    // ---------------------------------------------------------------- 造数

    private void partitions(LocalDate from, LocalDate to) {
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + DetailPartitions.partitionName(d)
                        + " PARTITION OF " + DetailPartitions.PARENT + " FOR VALUES FROM ('" + d + "') TO ('"
                        + d.plusDays(1) + "')");
            }
        });
    }

    private static Instant wib(LocalDate d, int hour, int minute) {
        return d.atTime(hour, minute).atZone(EventTimeCorrector.WIB).toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static String anon() {
        return UUID.randomUUID().toString();
    }

    private void event(String event, String anon, Long userId, LocalDate day, int hour, int minute,
            boolean production, boolean suspicious, String propsJson) {
        Instant at = wib(day, hour, minute);
        analyticsJdbc.update("""
                INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, is_suspicious, source,
                                           anonymous_id, user_id, user_key, platform, is_production, properties)
                VALUES (?, ?, ?, ?, ?, ?, 'APP', ?, ?, ?, 'android', ?, ?::jsonb)""",
                prefix + "det-" + SEQ.incrementAndGet(), event, utc(at), utc(at), EventTimeCorrector.wibDate(at),
                suspicious, anon, userId, userId == null ? null : AnalyticsDistinctId.of(userId), production,
                propsJson);
    }

    /** 生产、非可疑的事件（hour 可带分钟：传 minute）。 */
    private void ev(String event, String anon, Long userId, LocalDate day, int hour, int minute) {
        event(event, anon, userId, day, hour, minute, true, false, "{}");
    }

    private void tokoPage(String anon, Long userId, LocalDate day, int hour, int minute) {
        event("screen_viewed", anon, userId, day, hour, minute, true, false, "{\"screen\":\"toko_page\"}");
    }

    /** s1–s6 全路径（s4 用 cartOrBuy，s6 用 payEvent），从 hour 起每小时一步。 */
    private void path(String anon, Long userId, LocalDate day, int hour, String cartOrBuy, String payEvent) {
        tokoPage(anon, userId, day, hour, 0);
        ev("toko_product_shown", anon, userId, day, hour + 1, 0);
        ev("toko_product_detail_viewed", anon, userId, day, hour + 2, 0);
        ev(cartOrBuy, anon, userId, day, hour + 3, 0);
        ev("toko_order_submitted", anon, userId, day, hour + 4, 0);
        ev(payEvent, anon, userId, day, hour + 5, 0);
    }

    private void link(String anon, long userId, LocalDate day, int hour) {
        analyticsJdbc.update("INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id) "
                + "VALUES (?, ?, ?, ?, ?)", anon, userId, AnalyticsDistinctId.of(userId), utc(wib(day, hour, 0)),
                prefix + "link-" + SEQ.incrementAndGet());
    }

    private long user() {
        return newUser().getId();
    }

    private long vetUser() {
        long id = newUser().getId();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", id);
        return id;
    }

    /** 订单：createdAt 必填；paidAt 为空 = 未付款；status 可为取消 / 退款（付款后仍算已付款，D-13）。 */
    private void order(long userId, String status, Instant createdAt, Instant paidAt) {
        String token = tok + "o" + SEQ.incrementAndGet();
        businessJdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at, paid_at)
                VALUES (?, ?, ?, ?, 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', ?, ?, ?)
                """, token, "IT-TOKO-" + SEQ.incrementAndGet() % 100_000_000, userId, status, utc(createdAt),
                utc(createdAt), paidAt == null ? null : utc(paidAt));
    }

    // ---------------------------------------------------------------- 运行与读取

    private void run(LocalDate from, LocalDate to) {
        assertThat(service.rerun(from, to, List.of(TokoFamily.NAME), asOf).outcome()).isEqualTo(Outcome.DONE);
    }

    private Map<String, Object> row(String table, String keyCol, LocalDate key) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT * FROM " + table + " WHERE " + keyCol + " = ?", key);
        assertThat(rows).as("%s %s", table, key).hasSize(1);
        return rows.get(0);
    }

    private Map<String, Long> metrics(String table, String keyCol, LocalDate key) {
        Map<String, Object> r = row(table, keyCol, key);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String m : METRICS) {
            out.put(m, ((Number) r.get(m)).longValue());
        }
        return out;
    }

    private Map<String, Long> daily(LocalDate d) {
        return metrics("agg_daily_toko", "stat_date", d);
    }

    private Map<String, Long> weekly(LocalDate monday) {
        return metrics("agg_weekly_toko", "week_start", monday);
    }

    private Map<String, Long> monthly(LocalDate first) {
        return metrics("agg_monthly_toko", "month_start", first);
    }

    private static Map<String, Long> delta(Map<String, Long> after, Map<String, Long> before) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String m : METRICS) {
            out.put(m, after.get(m) - before.get(m));
        }
        return out;
    }

    /** 直接查业务库：paid_at 落在 WIB 日 d 的已付款订单数（下单人 USER + REAL）。 */
    private long directPaid(LocalDate from, LocalDate toInclusive) {
        Long n = businessJdbc.queryForObject("SELECT count(*) FROM shop_orders o JOIN users u ON u.id = o.user_id "
                + "WHERE o.paid_at >= ? AND o.paid_at < ? AND u.role = 'USER' AND u.account_type = 'REAL'",
                Long.class, utc(wib(from, 0, 0)), utc(wib(toInclusive.plusDays(1), 0, 0)));
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- 场景

    /**
     * 造 AC8 的全部场景（全部以 X 为基准）。日 X 期望增量：s1 8、s2 5、s3–s6 4、s7 3、提交 5、已付款 3、建单 4、加购 5、转化 2。
     */
    private void seed() {
        // P1：全路径（加购），X+1 付款 → 到 s7；再提交一次（提交条数 +1，人数不变）；X+1 又浏览一次（周表去重）
        long u1 = user();
        String a1 = anon();
        path(a1, u1, x, 9, "toko_add_to_cart_tapped", "toko_order_pay_tapped");
        ev("toko_order_submitted", a1, u1, x, 13, 30);
        order(u1, "PENDING_SHIPMENT", wib(x.plusDays(1), 9, 0), wib(x.plusDays(1), 10, 0));
        tokoPage(a1, u1, x.plusDays(1), 10, 0);
        // P2：跳步（有 s3、s4 无 s2）→ 只到 s1；加购但无订单
        long u2 = user();
        String a2 = anon();
        tokoPage(a2, u2, x, 9, 0);
        ev("toko_product_detail_viewed", a2, u2, x, 10, 0);
        ev("toko_add_to_cart_tapped", a2, u2, x, 11, 0);
        order(u2, "PENDING_PAYMENT", wib(x, 20, 0), null);
        // P3：s2 超过 7 天（X+8）→ 只到 s1
        long u3 = user();
        String a3 = anon();
        tokoPage(a3, u3, x, 9, 0);
        ev("toko_product_shown", a3, u3, x.plusDays(8), 10, 0);
        // P4：顺序颠倒（s3 在 s2 之前）→ 到 s2
        long u4 = user();
        String a4 = anon();
        tokoPage(a4, u4, x, 9, 0);
        ev("toko_product_detail_viewed", a4, u4, x, 10, 0);
        ev("toko_product_shown", a4, u4, x, 11, 0);
        // P5：WIB 23:30 → 归 X；P6：WIB 次日 00:30（UTC 仍是 X）→ 归 X+1
        tokoPage(anon(), user(), x, 23, 30);
        tokoPage(anon(), user(), x.plusDays(1), 0, 30);
        // 排除：非生产、可疑、兽医本人、匿名经关联归到兽医
        event("screen_viewed", anon(), user(), x, 9, 0, false, false, "{\"screen\":\"toko_page\"}");
        event("screen_viewed", anon(), user(), x, 9, 0, true, true, "{\"screen\":\"toko_page\"}");
        long vet = vetUser();
        tokoPage(anon(), vet, x, 9, 0);
        String av = anon();
        tokoPage(av, null, x, 8, 0);
        link(av, vet, x, 9);
        order(vet, "COMPLETED", wib(x, 11, 0), wib(x, 12, 0));
        // '/shop' 路径模板不算第 1 步
        event("screen_viewed", anon(), user(), x, 9, 0, true, false, "{\"screen\":\"/shop\"}");
        // P9：登录前匿名浏览到 s6（立即购买），15 点登录，16 点付款 → 归同一人、到 s7
        long u9 = user();
        String a9 = anon();
        path(a9, null, x, 9, "toko_buy_now_tapped", "toko_order_pay_tapped");
        link(a9, u9, x, 15);
        order(u9, "PENDING_SHIPMENT", wib(x, 15, 0), wib(x, 16, 0));
        // P10：纯 PawCoin 单，不经 pay_tapped，直接 payment_succeeded → s6、s7
        long u10 = user();
        String a10 = anon();
        path(a10, u10, x, 8, "toko_add_to_cart_tapped", "toko_order_payment_succeeded");
        order(u10, "PENDING_SHIPMENT", wib(x, 14, 0), wib(x, 15, 0));
        // Pa：从未登录的匿名人走到 s6 → s7 不通过；加购只进分母
        path(anon(), null, x, 9, "toko_add_to_cart_tapped", "toko_order_pay_tapped");
        // P11：加购，8 天后才付款 → 不算转化
        long u11 = user();
        ev("toko_add_to_cart_tapped", anon(), u11, x, 10, 0);
        order(u11, "COMPLETED", wib(x.plusDays(8), 10, 0), wib(x.plusDays(8), 11, 0));
        // 付款后取消仍算已付款（只影响 orders_paid / 建单）
        order(user(), "CANCELLED", wib(x, 17, 0), wib(x, 18, 0));
    }

    /** AC1–AC4、AC8、AC9：日表各场景、周表 ≠ 日表之和、is_final、与业务库一致、幂等。 */
    @Test
    void dailyAndWeeklyFunnelScenarios() {
        LocalDate x1 = x.plusDays(1);
        run(x, x.plusDays(6));
        Map<String, Long> d0 = daily(x);
        Map<String, Long> d1 = daily(x1);
        Map<String, Long> w0 = weekly(x);

        seed();
        run(x, x.plusDays(6));

        Map<String, Long> dx = delta(daily(x), d0);
        assertThat(dx).containsEntry("toko_viewers", 8L).containsEntry("funnel_s1_toko_page", 8L)
                .containsEntry("funnel_s2_product_shown", 5L).containsEntry("funnel_s3_detail_viewed", 4L)
                .containsEntry("funnel_s4_buy_or_cart", 4L).containsEntry("funnel_s5_order_submitted", 4L)
                .containsEntry("funnel_s6_pay_tapped", 4L).containsEntry("funnel_s7_paid", 3L)
                .containsEntry("orders_submitted", 5L).containsEntry("orders_paid", 3L)
                .containsEntry("orders_created_biz", 4L).containsEntry("atc_users", 5L)
                .containsEntry("atc_converted_users", 2L);

        // 跨午夜：P6 与 P1 的第二次浏览归 X+1（P1 在 X+1 的组只有 s1）；P1 的订单按付款日归 X+1
        Map<String, Long> dx1 = delta(daily(x1), d1);
        assertThat(dx1).containsEntry("funnel_s1_toko_page", 2L).containsEntry("funnel_s2_product_shown", 0L)
                .containsEntry("orders_paid", 1L).containsEntry("orders_created_biz", 1L);

        // D-12：周表人数 ≠ 日表人数之和（P1 在 X、X+1 都浏览，周内只算一次）
        Map<String, Long> dw = delta(weekly(x), w0);
        assertThat(dw.get("funnel_s1_toko_page")).isEqualTo(9L)
                .isNotEqualTo(dx.get("funnel_s1_toko_page") + dx1.get("funnel_s1_toko_page"));
        assertThat(dw).containsEntry("funnel_s7_paid", 3L).containsEntry("atc_users", 5L)
                .containsEntry("atc_converted_users", 2L).containsEntry("orders_submitted", 5L);

        // is_final：X 的窗口早已关闭
        assertThat(row("agg_daily_toko", "stat_date", x).get("is_final")).isEqualTo(true);
        assertThat(row("agg_weekly_toko", "week_start", x).get("is_final")).isEqualTo(true);

        // AC9：orders_paid = 直接查业务库（USER + REAL 下单人）
        assertThat(daily(x).get("orders_paid")).isEqualTo(directPaid(x, x));
        assertThat(daily(x1).get("orders_paid")).isEqualTo(directPaid(x1, x1));
        assertThat(weekly(x).get("orders_paid")).isEqualTo(directPaid(x, x.plusDays(6)));

        // 幂等：重跑结果相同
        Map<String, Long> again = daily(x);
        Map<String, Long> againW = weekly(x);
        run(x, x.plusDays(6));
        assertThat(daily(x)).isEqualTo(again);
        assertThat(weekly(x)).isEqualTo(againW);

        // 汇总表没有可识别个人的列
        List<String> cols = analyticsJdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name IN ('agg_daily_toko', 'agg_weekly_toko', 'agg_monthly_toko')", String.class);
        assertThat(cols).isNotEmpty().doesNotContain("user_id", "anonymous_id", "person_key", "user_key");
    }

    /** AC2 / D-12：月表在月末计算，组 = 月内首次浏览的人；与业务库一致。 */
    @Test
    void monthlyDistinctAndConsistentWithBusiness() {
        LocalDate first = x.withDayOfMonth(1);
        LocalDate monthEnd = x.with(TemporalAdjusters.lastDayOfMonth());
        run(monthEnd, monthEnd);
        Map<String, Long> m0 = monthly(first);

        seed();
        run(monthEnd, monthEnd);

        Map<String, Long> dm = delta(monthly(first), m0);
        assertThat(dm).containsEntry("funnel_s1_toko_page", 9L).containsEntry("toko_viewers", 9L)
                .containsEntry("funnel_s7_paid", 3L).containsEntry("atc_users", 5L)
                .containsEntry("atc_converted_users", 2L).containsEntry("orders_submitted", 5L)
                .containsEntry("orders_paid", 5L);
        assertThat(monthly(first).get("orders_paid")).isEqualTo(directPaid(first, monthEnd));
        assertThat(row("agg_monthly_toko", "month_start", first).get("is_final"))
                .isEqualTo(!monthEnd.isAfter(asOf.minusDays(7)));
    }

    /** AC5：定时运行重算 [Y − 7, Y]：造数后只跑定时，最近 8 天内的日期被刷新。 */
    @Test
    void scheduledRunRecomputesLast8Days() {
        LocalDate y = asOf;
        LocalDate d = y.minusDays(7);
        partitions(d, d);
        run(d, d);
        Map<String, Long> before = daily(d);
        tokoPage(anon(), user(), d, 10, 0);
        assertThat(service.runScheduled(y).outcome()).isEqualTo(Outcome.DONE);
        assertThat(delta(daily(d), before)).containsEntry("funnel_s1_toko_page", 1L);
        assertThat(row("agg_daily_toko", "stat_date", d).get("is_final")).isEqualTo(true);
        assertThat(row("agg_daily_toko", "stat_date", y).get("is_final")).isEqualTo(false);
    }
}
