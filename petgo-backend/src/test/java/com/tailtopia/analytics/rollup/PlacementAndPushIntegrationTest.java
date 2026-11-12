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
 * L1：运营位（{@code placement}）与推送（{@code push}）指标族（analytics-pipeline Story 4.4 AC1–AC7 / T8），
 * 真业务库 + 埋点测试库 {@code petgo_analytics_test}（前置同 {@code RollupActivityInstallIntegrationTest}）。
 *
 * <ul>
 *   <li>隔离：运营位 token、运营推送活动 token、自造的 {@code push_type} 每个测试唯一，直接断言绝对值；券过期推送行
 *       （{@code COUPON_EXPIRING}）全库共用，按「先跑一次取基线 → 造数 → 再跑」取增量。</li>
 *   <li>统计日 X 取 WIB 今天往前约 6 周的周一（同一周 / 同一月内），{@code as_of = X + 12}；分区按需建。</li>
 *   <li>明细 / 关联按 {@code message_id} 前缀清理；业务库运营位、活动、订单按 token 前缀清理。</li>
 * </ul>
 */
class PlacementAndPushIntegrationTest extends ApiIntegrationTest {

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
                new RollupProgressRepository(analyticsJdbc),
                List.of(new PlacementFamily(reader), new PushFamily(reader, PushRollupProperties.defaults(), alerts,
                        "test")),
                AnalyticsRollupProperties.defaults(), alerts, "test", Clock.systemUTC());
        long n = SEQ.incrementAndGet();
        prefix = "it-plc-" + n + "-";
        tok = "itp" + Long.toString(n % 1_000_000_000_000L, 36);
        LocalDate todayWib = EventTimeCorrector.wibDate(Instant.now());
        x = todayWib.minusDays(42).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (x.getDayOfMonth() > 20) {
            // X 与 X+1 要在同一个月（月表断言），X 取周一且不贴月末
            x = x.plusWeeks(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        asOf = x.plusDays(12);
        partitions(x.minusDays(1), x.plusDays(9));
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM identity_links WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        for (String t : new String[] {"agg_daily_placement", "agg_weekly_placement", "agg_monthly_placement"}) {
            analyticsJdbc.update("DELETE FROM " + t + " WHERE placement_token LIKE ?", tok + "%");
        }
        for (String t : new String[] {"agg_daily_push", "agg_weekly_push", "agg_monthly_push"}) {
            analyticsJdbc.update("DELETE FROM " + t + " WHERE campaign_token LIKE ? OR push_type LIKE ?", tok + "%",
                    "IT_" + tok.toUpperCase() + "%");
        }
        businessJdbc.update("DELETE FROM shop_orders WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM ops_popups WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM shop_banners WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM ops_notification_campaigns WHERE public_token LIKE ?", tok + "%");
    }

    // ---------------------------------------------------------------- 造数：埋点库

    private void partitions(LocalDate from, LocalDate to) {
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + DetailPartitions.partitionName(d)
                        + " PARTITION OF " + DetailPartitions.PARENT + " FOR VALUES FROM ('" + d + "') TO ('"
                        + d.plusDays(1) + "')");
            }
        });
    }

    private static Instant wib(LocalDate d, int hour) {
        return d.atTime(hour, 0).atZone(EventTimeCorrector.WIB).toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static String anon() {
        return UUID.randomUUID().toString();
    }

    private void event(String event, String source, String anon, Long userId, LocalDate day, int hour,
            boolean production, boolean suspicious, String propsJson) {
        Instant at = wib(day, hour);
        analyticsJdbc.update("""
                INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, is_suspicious, source,
                                           anonymous_id, user_id, user_key, platform, is_production, properties)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'android', ?, ?::jsonb)""",
                prefix + "det-" + SEQ.incrementAndGet(), event, utc(at), utc(at), day, suspicious, source, anon, userId,
                userId == null ? null : AnalyticsDistinctId.of(userId), production, propsJson);
    }

    /** 生产、非可疑、APP 的运营位事件。 */
    private void placement(String event, String type, String token, String anon, Long userId, LocalDate day,
            int hour) {
        event(event, "APP", anon, userId, day, hour, true, false, placementProps(type, token, null));
    }

    private static String placementProps(String type, String token, String action) {
        return "{\"placement_type\":\"" + type + "\",\"placement_token\":\"" + token + "\""
                + (action == null ? "" : ",\"action\":\"" + action + "\"") + "}";
    }

    private void pushOpened(String pushType, String campaign, String anon, Long userId, LocalDate day, int hour) {
        event("push_opened", "APP", anon, userId, day, hour, true, false,
                "{\"push_type\":\"" + pushType + "\"" + (campaign == null ? "" : ",\"campaign_token\":\"" + campaign
                        + "\"") + "}");
    }

    private void link(String anon, long userId, LocalDate day, int hour) {
        analyticsJdbc.update("INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id) "
                + "VALUES (?, ?, ?, ?, ?)", anon, userId, AnalyticsDistinctId.of(userId), utc(wib(day, hour)),
                prefix + "link-" + SEQ.incrementAndGet());
    }

    // ---------------------------------------------------------------- 造数：业务库

    private long vetUser() {
        long id = newUser().getId();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", id);
        return id;
    }

    private String popup(String suffix, String targetType, String targetValue) {
        String token = tok + suffix;
        businessJdbc.update("""
                INSERT INTO ops_popups (public_token, name, starts_at, image_url_id, button_text_id, target_type,
                                        target_value)
                VALUES (?, 'it', now() - interval '60 days', 'https://x/1.jpg', 'OK', ?, ?)""",
                token, targetType, targetValue);
        return token;
    }

    private String banner(String suffix) {
        String token = tok + suffix;
        // 未上架（active = false）：不影响共享库里读「当前横幅」的其他测试；统计只看 token 是否还在
        businessJdbc.update("INSERT INTO shop_banners (public_token, image_key, image_w, image_h, active, sort_weight, "
                + "target_type, target_value) VALUES (?, ?, 1600, 900, false, 0, 'PRODUCT', 'p1')", token,
                "shop-banner/" + token + ".jpg");
        return token;
    }

    /** 已付款（paidAt 非空）或未付款订单；status 可为取消 / 退款（付款后仍算已付款）。 */
    private void order(long userId, String status, Instant paidAt) {
        String token = tok + "o" + SEQ.incrementAndGet();
        businessJdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at, paid_at)
                VALUES (?, ?, ?, ?, 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now(), ?)
                """, token, "IT-PLC-" + SEQ.incrementAndGet() % 100_000_000, userId, status,
                paidAt == null ? null : utc(paidAt));
    }

    private String campaign(String suffix, String targetType, String targetValue, Integer planned, int recipients,
            Instant startedAt) {
        String token = tok + suffix;
        businessJdbc.update("""
                INSERT INTO ops_notification_campaigns (public_token, title_id, title_en, body_id, body_en,
                        target_type, target_value, audience_spec, status, recipient_count, planned_count, started_at)
                VALUES (?, 'a', 'a', 'b', 'b', ?, ?, '{"mode":"ALL"}'::jsonb, 'DONE', ?, ?, ?)""",
                token, targetType, targetValue, recipients, planned, utc(startedAt));
        return token;
    }

    // ---------------------------------------------------------------- 读结果

    private void run(String... families) {
        assertThat(service.rerun(x, x.plusDays(2), List.of(families), asOf).outcome()).isEqualTo(Outcome.DONE);
    }

    private Map<String, Object> dailyPlacement(LocalDate d, String type, String token) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList("SELECT * FROM agg_daily_placement "
                + "WHERE stat_date = ? AND placement_type = ? AND placement_token = ?", d, type, token);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> periodPlacement(String table, LocalDate start, String type, String token) {
        return analyticsJdbc.queryForMap("SELECT * FROM " + table
                + " WHERE period_start = ? AND placement_type = ? AND placement_token = ?", start, type, token);
    }

    private Map<String, Object> dailyPush(LocalDate d, String pushType, String campaign) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList("SELECT * FROM agg_daily_push "
                + "WHERE stat_date = ? AND push_type = ? AND campaign_token = ?", d, pushType, campaign);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Long n(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : ((Number) v).longValue();
    }

    // ---------------------------------------------------------------- AC1 / AC2 / AC6：曝光、点击、带单

    @Test
    void impressionsClicksAndPaidOrdersWithin7Days() {
        long u1 = newUser().getId();
        String a1 = anon();
        String p1 = popup("p1", "PRODUCT", "prodTok");
        // 曝光 3 次、点击 2 次（同一人两次）
        placement("ops_popup_shown", "ops_popup", p1, a1, u1, x, 9);
        placement("ops_popup_shown", "ops_popup", p1, a1, u1, x, 11);
        placement("ops_popup_shown", "ops_popup", p1, anon(), null, x, 11);
        placement("ops_popup_tapped", "ops_popup", p1, a1, u1, x, 10);
        placement("ops_popup_tapped", "ops_popup", p1, a1, u1, x, 12);
        // 关闭不算点击
        placement("ops_popup_closed", "ops_popup", p1, a1, u1, x, 12);
        // 订单：6 天后计入、8 天后不计、点击前不计、未付款不计、付款后取消仍计；X+1 的订单被两次点击匹配只计 1 单
        order(u1, "PENDING_SHIPMENT", wib(x.plusDays(1), 9));
        order(u1, "COMPLETED", wib(x.plusDays(6), 9));
        order(u1, "COMPLETED", wib(x.plusDays(8), 10));
        order(u1, "COMPLETED", wib(x, 8));
        order(u1, "PENDING_PAYMENT", null);
        order(u1, "CANCELLED", wib(x.plusDays(2), 9));

        run("placement");
        Map<String, Object> row = dailyPlacement(x, "ops_popup", p1);
        assertThat(row).isNotNull();
        assertThat(n(row, "impressions")).isEqualTo(3);
        assertThat(n(row, "clicks")).isEqualTo(2);
        assertThat(n(row, "click_persons")).isEqualTo(1);
        assertThat(n(row, "paid_orders_7d")).isEqualTo(3);
        assertThat(row.get("promotes_toko")).isEqualTo(true);
        assertThat(row.get("orders_window_complete")).isEqualTo(true);

        // AC6 抽样一致：直接查业务库 shop_orders 的应计订单数（首次点击之后、最后一次点击 + 7 天之内的已付款订单）
        Long direct = businessJdbc.queryForObject("SELECT count(DISTINCT public_token) FROM shop_orders "
                + "WHERE user_id = ? AND paid_at IS NOT NULL AND paid_at > ? AND paid_at <= ? + interval '7 days'",
                Long.class, u1, utc(wib(x, 10)), utc(wib(x, 12)));
        assertThat(n(row, "paid_orders_7d")).isEqualTo(direct);

        // 汇总表没有可识别个人的列
        List<String> cols = analyticsJdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name LIKE 'agg\\_%placement' OR table_name LIKE 'agg\\_%push'", String.class);
        assertThat(cols).isNotEmpty().doesNotContain("user_id", "anonymous_id", "person_key", "user_key",
                "order_token");
    }

    /** 匿名点击后登录（关联 A→U2）、U2 付款 → 按 D-10 归属计入；横幅一律推广 Toko。 */
    @Test
    void anonymousClickAttributedAfterLogin() {
        long u2 = newUser().getId();
        String a = anon();
        String b1 = banner("b1");
        placement("toko_banner_shown", "toko_banner", b1, a, null, x, 8);
        placement("toko_banner_tapped", "toko_banner", b1, a, null, x, 9);
        link(a, u2, x, 11);
        order(u2, "PENDING_SHIPMENT", wib(x.plusDays(1), 10));
        // 匿名且从未登录的点击：计入点击人数，但无法匹配订单
        placement("toko_banner_tapped", "toko_banner", b1, anon(), null, x, 9);

        run("placement");
        Map<String, Object> row = dailyPlacement(x, "toko_banner", b1);
        assertThat(n(row, "clicks")).isEqualTo(2);
        assertThat(n(row, "click_persons")).isEqualTo(2);
        assertThat(n(row, "paid_orders_7d")).isEqualTo(1);
        assertThat(row.get("promotes_toko")).isEqualTo(true);
    }

    // ---------------------------------------------------------------- AC3：推广 Toko 维度

    @Test
    void promotesTokoFromBusinessConfig() {
        String pExt = popup("ext", "EXTERNAL", "https://example.com");
        String pPage = popup("page", "APP_PAGE", "TOKO");
        String pHome = popup("home", "APP_PAGE", "HOME");
        String pGone = tok + "gone"; // 配置已删
        String nb = tok + "nb";
        for (String p : new String[] {pExt, pPage, pHome, pGone}) {
            placement("ops_popup_shown", "ops_popup", p, anon(), null, x, 9);
        }
        placement("toko_newbie_coupon_popup_shown", "newbie_coupon", nb, anon(), null, x, 9);
        String na = anon();
        event("toko_newbie_coupon_popup_tapped", "APP", na, null, x, 9, true, false,
                placementProps("newbie_coupon", nb, "register"));
        event("toko_newbie_coupon_popup_tapped", "APP", na, null, x, 10, true, false,
                placementProps("newbie_coupon", nb, "close"));
        // 属性与事件名不一致 / 缺 token：跳过
        placement("ops_popup_shown", "toko_banner", pExt, anon(), null, x, 9);
        event("ops_popup_shown", "APP", anon(), null, x, 9, true, false, "{\"placement_type\":\"ops_popup\"}");

        run("placement");
        assertThat(dailyPlacement(x, "ops_popup", pExt).get("promotes_toko")).isEqualTo(false);
        assertThat(n(dailyPlacement(x, "ops_popup", pExt), "impressions")).isEqualTo(1);
        assertThat(dailyPlacement(x, "toko_banner", pExt)).isNull();
        assertThat(dailyPlacement(x, "ops_popup", pPage).get("promotes_toko")).isEqualTo(true);
        assertThat(dailyPlacement(x, "ops_popup", pHome).get("promotes_toko")).isEqualTo(false);
        assertThat(dailyPlacement(x, "ops_popup", pGone).get("promotes_toko")).isNull();
        Map<String, Object> newbie = dailyPlacement(x, "newbie_coupon", nb);
        assertThat(newbie.get("promotes_toko")).isEqualTo(true);
        assertThat(n(newbie, "clicks")).as("close 不算点击").isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC1 / D-12：周 / 月去重；排除；幂等

    @Test
    void periodDistinctPersonsExclusionsAndIdempotent() {
        long u3 = newUser().getId();
        String a3 = anon();
        String p4 = popup("p4", "CATEGORY", "DRY_FOOD");
        placement("ops_popup_tapped", "ops_popup", p4, a3, u3, x, 10);
        placement("ops_popup_tapped", "ops_popup", p4, a3, u3, x.plusDays(1), 10);
        order(u3, "COMPLETED", wib(x.plusDays(1), 12));
        // 排除：非生产、可疑、兽医本人、匿名经关联归到兽医
        long vet = vetUser();
        event("ops_popup_tapped", "APP", anon(), u3, x, 11, false, false, placementProps("ops_popup", p4, null));
        event("ops_popup_tapped", "APP", anon(), u3, x, 11, true, true, placementProps("ops_popup", p4, null));
        placement("ops_popup_tapped", "ops_popup", p4, anon(), vet, x, 11);
        String av = anon();
        placement("ops_popup_tapped", "ops_popup", p4, av, null, x, 8);
        link(av, vet, x, 9);
        order(vet, "COMPLETED", wib(x, 12));

        run("placement");
        assertThat(n(dailyPlacement(x, "ops_popup", p4), "clicks")).isEqualTo(1);
        assertThat(n(dailyPlacement(x, "ops_popup", p4), "click_persons")).isEqualTo(1);
        assertThat(n(dailyPlacement(x.plusDays(1), "ops_popup", p4), "click_persons")).isEqualTo(1);
        Map<String, Object> week = periodPlacement("agg_weekly_placement", x, "ops_popup", p4);
        assertThat(n(week, "clicks")).isEqualTo(2);
        assertThat(n(week, "click_persons")).as("周内去重，不是日表相加").isEqualTo(1);
        assertThat(n(week, "paid_orders_7d")).isEqualTo(1);
        assertThat(week.get("is_complete")).isEqualTo(true);
        Map<String, Object> month = periodPlacement("agg_monthly_placement", x.withDayOfMonth(1), "ops_popup", p4);
        assertThat(n(month, "click_persons")).isEqualTo(1);
        assertThat(n(month, "paid_orders_7d")).isEqualTo(1);

        // 重跑两次结果相同
        List<Map<String, Object>> before = analyticsJdbc.queryForList("SELECT stat_date, impressions, clicks, "
                + "click_persons, paid_orders_7d, promotes_toko FROM agg_daily_placement WHERE placement_token = ? "
                + "ORDER BY stat_date", p4);
        run("placement");
        assertThat(analyticsJdbc.queryForList("SELECT stat_date, impressions, clicks, click_persons, paid_orders_7d, "
                + "promotes_toko FROM agg_daily_placement WHERE placement_token = ? ORDER BY stat_date", p4))
                .isEqualTo(before);
    }

    // ---------------------------------------------------------------- AC4 / AC7：推送

    @Test
    void opsCampaignCountsAndCumulativeOpens() {
        long u1 = newUser().getId();
        String a1 = anon();
        String c1 = campaign("c1", "APP_PAGE", "TOKO", 10, 8, wib(x, 8));
        String cExt = campaign("c2", "EXTERNAL", "https://x", null, 3, wib(x, 8));
        // 同一人两天各打开一次（第一天两次）
        pushOpened("OPS_ANNOUNCEMENT", c1, a1, u1, x, 10);
        pushOpened("OPS_ANNOUNCEMENT", c1, a1, u1, x, 11);
        pushOpened("OPS_ANNOUNCEMENT", c1, a1, u1, x.plusDays(1), 10);
        // 兽医打开排除（3-2 复审 R4）
        pushOpened("OPS_ANNOUNCEMENT", c1, anon(), vetUser(), x.plusDays(1), 10);

        run("push");
        Map<String, Object> d0 = dailyPush(x, "OPS_ANNOUNCEMENT", c1);
        assertThat(n(d0, "planned_count")).isEqualTo(10);
        assertThat(n(d0, "recipient_count")).isEqualTo(8);
        assertThat(d0.get("promotes_toko")).isEqualTo(true);
        assertThat(n(d0, "opened_count")).isEqualTo(2);
        assertThat(n(d0, "opened_persons")).isEqualTo(1);
        assertThat(n(d0, "campaign_opened_persons_cum")).isEqualTo(1);
        assertThat(n(d0, "sent_count")).isNull();
        Map<String, Object> d1 = dailyPush(x.plusDays(1), "OPS_ANNOUNCEMENT", c1);
        assertThat(n(d1, "opened_persons")).isEqualTo(1);
        assertThat(n(d1, "campaign_opened_persons_cum")).as("两天打开的同一人累计 1 人").isEqualTo(1);
        // 跟踪期内当天无打开也有行，累计值不变
        Map<String, Object> d2 = dailyPush(x.plusDays(2), "OPS_ANNOUNCEMENT", c1);
        assertThat(n(d2, "opened_count")).isZero();
        assertThat(n(d2, "campaign_opened_persons_cum")).isEqualTo(1);
        assertThat(n(d2, "recipient_count")).isEqualTo(8);
        // 与业务表一致
        Map<String, Object> biz = businessJdbc.queryForMap("SELECT planned_count, recipient_count FROM "
                + "ops_notification_campaigns WHERE public_token = ?", c1);
        assertThat(n(d2, "planned_count")).isEqualTo(n(biz, "planned_count"));
        assertThat(n(d2, "recipient_count")).isEqualTo(n(biz, "recipient_count"));
        // 跳外链的活动不推广 Toko、存量 planned_count 为空；无人打开累计 0
        Map<String, Object> ext = dailyPush(x, "OPS_ANNOUNCEMENT", cExt);
        assertThat(ext.get("promotes_toko")).isEqualTo(false);
        assertThat(n(ext, "planned_count")).isNull();
        assertThat(n(ext, "campaign_opened_persons_cum")).isZero();
        // 周表：打开人数周内去重
        Map<String, Object> week = analyticsJdbc.queryForMap("SELECT opened_count, opened_persons FROM agg_weekly_push "
                + "WHERE period_start = ? AND push_type = 'OPS_ANNOUNCEMENT' AND campaign_token = ?", x, c1);
        assertThat(n(week, "opened_count")).isEqualTo(3);
        assertThat(n(week, "opened_persons")).isEqualTo(1);
    }

    @Test
    void couponExpirySentCountAndOtherTypesOpensOnly() {
        run("push");
        Map<String, Object> base = dailyPush(x, "COUPON_EXPIRING", "");
        assertThat(base).as("券过期行每天必写").isNotNull();
        long baseSent = n(base, "sent_count");

        long u1 = newUser().getId();
        for (int i = 0; i < 3; i++) {
            event("toko_coupon_expiry_push_sent", "SERVER", null, u1, x, 10 + i, true, false, "{\"coupon_count\":1}");
        }
        // 非生产与兽医的发送不计
        event("toko_coupon_expiry_push_sent", "SERVER", null, u1, x, 14, false, false, "{\"coupon_count\":1}");
        event("toko_coupon_expiry_push_sent", "SERVER", null, vetUser(), x, 14, true, false, "{\"coupon_count\":1}");
        // 其他类型：只记打开
        String other = "IT_" + tok.toUpperCase();
        String a1 = anon();
        pushOpened(other, null, a1, u1, x, 10);
        pushOpened(other, "should-be-ignored", a1, u1, x, 11);
        pushOpened(other, null, a1, u1, x.plusDays(1), 10);

        run("push");
        assertThat(n(dailyPush(x, "COUPON_EXPIRING", ""), "sent_count") - baseSent).isEqualTo(3);
        Map<String, Object> o = dailyPush(x, other, "");
        assertThat(n(o, "sent_count")).isNull();
        assertThat(n(o, "opened_count")).isEqualTo(2);
        assertThat(n(o, "opened_persons")).isEqualTo(1);
        assertThat(n(o, "planned_count")).isNull();
        assertThat(n(o, "campaign_opened_persons_cum")).isNull();
        Map<String, Object> week = analyticsJdbc.queryForMap("SELECT sent_count, opened_count, opened_persons "
                + "FROM agg_weekly_push WHERE period_start = ? AND push_type = ? AND campaign_token = ''", x, other);
        assertThat(n(week, "sent_count")).isNull();
        assertThat(n(week, "opened_count")).isEqualTo(3);
        assertThat(n(week, "opened_persons")).isEqualTo(1);
        Map<String, Object> month = analyticsJdbc.queryForMap("SELECT opened_persons FROM agg_monthly_push "
                + "WHERE period_start = ? AND push_type = ? AND campaign_token = ''", x.withDayOfMonth(1), other);
        assertThat(n(month, "opened_persons")).isEqualTo(1);
    }
}
