package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.rollup.RollupService.Outcome;
import com.tailtopia.analytics.store.EventTimeCorrector;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：指标族 {@code ratio}（analytics-pipeline Story 4.7 AC1 / AC2 / AC7 / AC8 / T7），真业务库 + 埋点测试库
 * {@code petgo_analytics_test}（前置同 {@code RollupActivityInstallIntegrationTest}）。
 *
 * <ul>
 *   <li>隔离：统计日 X 取很早的一个随机日期（2019 年起），业务库在 X 前后没有别的数据；造的业务行按 token 前缀 / id 清理，
 *       埋点库 {@code agg_daily_ratio} / {@code ga4_daily_snapshot} / {@code agg_daily_activity} 按日期清理。</li>
 *   <li>AC8：构造数据集（PawCoin 与 QRIS 两种渠道各一笔、同人同日两笔只算一人、兽医 / 虚拟号 / 门店进货单被排除、
 *       UTC 与 WIB 跨日），并与直接查业务库的同日同口径结果比对。</li>
 *   <li>AC7：GA4 当天没有快照 → 分母 / 比率 / 回访为 NULL、分子照写；补上快照后重跑自动填上；SELF 取 agg_daily_activity 的 all 行。</li>
 * </ul>
 * 送礼（gift_orders 依赖礼物目录外键）不造数，只与直接查询比对。
 */
class RatioRollupIntegrationTest extends ApiIntegrationTest {

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

    private String tok;
    private LocalDate x;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        long n = SEQ.incrementAndGet();
        tok = "itr" + Long.toString(n % 1_000_000_000_000L, 36);
        x = LocalDate.of(2019, 1, 1).plusDays(Math.floorMod(n, 2000));
        cleanAnalytics();
    }

    @AfterEach
    void cleanUp() {
        cleanAnalytics();
        businessJdbc.update("DELETE FROM shop_orders WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM payment_intents WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM keepsake_purchases WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM pet_slot_purchases WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM ai_consult_orders WHERE order_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM consult_orders WHERE order_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM id_card_hd_purchases WHERE purchased_at >= ? AND purchased_at < ?",
                utc(wib(x.minusDays(1), 0, 0)), utc(wib(x.plusDays(2), 0, 0)));
    }

    private void cleanAnalytics() {
        for (String t : new String[] {"agg_daily_ratio", "ga4_daily_snapshot"}) {
            analyticsJdbc.update("DELETE FROM " + t + " WHERE stat_date BETWEEN ? AND ?", x.minusDays(1), x.plusDays(1));
        }
        analyticsJdbc.update("DELETE FROM agg_daily_activity WHERE stat_date BETWEEN ? AND ?", x.minusDays(1),
                x.plusDays(1));
    }

    // ---------------------------------------------------------------- 造数

    private static Instant wib(LocalDate d, int hour, int minute) {
        return d.atTime(hour, minute).atZone(EventTimeCorrector.WIB).toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private String token() {
        return tok + "t" + SEQ.incrementAndGet() % 1_000_000_000L;
    }

    /** USER + REAL，注册时间设到 {@code createdAt}。 */
    private long user(Instant createdAt) {
        long id = newUser().getId();
        businessJdbc.update("UPDATE users SET created_at = ? WHERE id = ?", utc(createdAt), id);
        return id;
    }

    private long vet(Instant createdAt) {
        long id = user(createdAt);
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", id);
        return id;
    }

    private long virtual(Instant createdAt) {
        long id = user(createdAt);
        businessJdbc.update("UPDATE users SET account_type = 'VIRTUAL' WHERE id = ?", id);
        return id;
    }

    private void shopOrder(long userId, String status, Instant paidAt) {
        businessJdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at, paid_at)
                VALUES (?, ?, ?, ?, 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', ?, ?, ?)
                """, token(), "IT-RATIO-" + SEQ.incrementAndGet() % 100_000_000, userId, status, utc(paidAt),
                utc(paidAt), utc(paidAt));
    }

    private void intent(long userId, String purpose, String channel, String status, Instant updatedAt) {
        businessJdbc.update("INSERT INTO payment_intents (public_token, user_id, purpose, channel, amount, status, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, 10000, ?, ?, ?)", token(), userId, purpose, channel,
                status, utc(updatedAt), utc(updatedAt));
    }

    private void ktpPawcoin(long userId, Instant purchasedAt) {
        businessJdbc.update("INSERT INTO id_card_hd_purchases (user_id, pay_channel, purchased_at) VALUES (?, 'PAWCOIN', ?)",
                userId, utc(purchasedAt));
    }

    private void keepsake(long userId, String sku, String channel, String status, Instant paidAt) {
        businessJdbc.update("INSERT INTO keepsake_purchases (public_token, sku, ref_id, user_id, price_idr, pay_channel, "
                + "status, created_at, paid_at) VALUES (?, ?, ?, ?, 15000, ?, ?, ?, ?)", token(), sku,
                SEQ.incrementAndGet(), userId, channel, status, utc(paidAt), utc(paidAt));
    }

    private void petSlot(long userId, String kind, String channel, Instant paidAt) {
        String t = token();
        businessJdbc.update("INSERT INTO pet_slot_purchases (public_token, user_id, kind, quantity, unit_price, total, "
                + "channel, idem_key, status, created_at, paid_at) VALUES (?, ?, ?, 1, 20000, 20000, ?, ?, 'PAID', ?, ?)",
                t, userId, kind, channel, "IT:" + t, utc(paidAt), utc(paidAt));
    }

    private void aiUnlock(long userId, String channel, String status, Instant paidAt) {
        businessJdbc.update("INSERT INTO ai_consult_orders (order_token, user_id, triage_task_id, amount, pay_channel, "
                + "status, paid_at) VALUES (?, ?, ?, 10000, ?, ?, ?)", token(), userId, SEQ.incrementAndGet(), channel,
                status, paidAt == null ? null : utc(paidAt));
    }

    private void vetConsult(long userId, String status, Instant paidAt) {
        businessJdbc.update("INSERT INTO consult_orders (order_token, user_id, vet_id, pet_profile_id, status, amount, "
                + "pay_channel, paid_at) VALUES (?, ?, ?, ?, ?, 50000, 'QRIS', ?)", token(), userId,
                SEQ.incrementAndGet(), SEQ.incrementAndGet(), status, utc(paidAt));
    }

    // ---------------------------------------------------------------- 运行与读取

    private RollupService service(String dauSource) {
        RatioRollup ratio = new RatioRollup(new PayerCountReader(businessJdbc), new AnalyticsDauSourceProperties(dauSource));
        return new RollupService(state, analyticsJdbc, analyticsTx, businessJdbc,
                new RollupProgressRepository(analyticsJdbc),
                List.of(new SqlRollupFamily("activity", 3, Set.of()), ratio),
                AnalyticsRollupProperties.defaults(), mock(OpsAlertService.class), "test", Clock.systemUTC());
    }

    private void run(String dauSource) {
        assertThat(service(dauSource).rerun(x, x, List.of(RatioRollup.NAME), x).outcome()).isEqualTo(Outcome.DONE);
    }

    private Map<String, Object> row() {
        return analyticsJdbc.queryForMap("SELECT * FROM agg_daily_ratio WHERE stat_date = ?", x);
    }

    private long direct(String sql) {
        Long n = businessJdbc.queryForObject(sql, Long.class, utc(wib(x, 0, 0)), utc(wib(x.plusDays(1), 0, 0)));
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- 用例

    /** AC1 / AC8：各分子与直接查业务库一致；构造集的期望值。 */
    @Test
    void numeratorsMatchBusinessDatabase() {
        Instant early = wib(x, 0, 30);       // WIB X 00:30 = UTC X−1 17:30：按 WIB 算 X
        Instant late = wib(x, 23, 30);       // WIB X 23:30 = UTC X 16:30
        Instant nextDay = wib(x.plusDays(1), 0, 30); // UTC X 17:30（UTC 仍是 X，但 WIB 是 X+1，不算）
        Instant prevDay = wib(x.minusDays(1), 23, 30);

        long a = user(early);   // 当天注册：Toko 两笔（同人同日只算一人）+ KTP QRIS
        long b = user(late);    // 当天注册：KTP PawCoin + Tailsonality PawCoin + 名额 RENT
        long c = user(prevDay); // 前一天注册：Toko 一笔（退款仍算）+ 护照 QRIS + AI 解锁 + 兽医问诊（已退款仍算）
        long d = user(nextDay); // 次日注册：只有跨日（WIB 次日）的付费 → 都不算
        long vet = vet(early);
        long virt = virtual(early);

        shopOrder(a, "PENDING_SHIPMENT", early);
        shopOrder(a, "COMPLETED", late);
        shopOrder(c, "REFUNDED", late);
        shopOrder(d, "PENDING_SHIPMENT", nextDay);
        shopOrder(vet, "PENDING_SHIPMENT", late);
        shopOrder(virt, "PENDING_SHIPMENT", late);

        intent(a, "ID_HD", "QRIS", "PAID", early);
        intent(d, "ID_HD", "QRIS", "PENDING", early);       // 未到账不算
        ktpPawcoin(b, late);
        keepsake(b, "TAILSONALITY", "PAWCOIN", "PAID", late);
        keepsake(a, "TS_MATCH", "QRIS", "ORPHAN_PAID", late); // 交人工，不算
        keepsake(c, "PASSPORT_SNAP", "QRIS", "PAID", early);
        keepsake(vet, "BOARDING_PASS", "PAWCOIN", "PAID", early);
        petSlot(b, "RENT", "QRIS", early);
        petSlot(a, "ADMIN_GRANT", null, early);              // 运营补发，不算
        aiUnlock(c, "PAWCOIN", "COMPLETED", late);
        aiUnlock(a, "QRIS", "PENDING_PAYMENT", null);
        vetConsult(c, "REFUNDED", early);
        vetConsult(d, "COMPLETED", nextDay);
        intent(a, "PAWCOIN_TOPUP", "QRIS", "PAID", late);   // 参照，不进产品 #3
        intent(-SEQ.incrementAndGet(), "MERCHANT_PO", "QRIS", "PAID", late); // 门店进货单：负 id，被 join users 排除

        run("GA4");
        Map<String, Object> r = row();

        // 与直接查业务库同日同口径一致（AC8）
        assertThat(r.get("toko_buyers")).isEqualTo((int) direct(
                "SELECT count(DISTINCT o.user_id) FROM shop_orders o JOIN users u ON u.id = o.user_id "
                + "WHERE o.paid_at >= ? AND o.paid_at < ? AND u.role = 'USER' AND u.account_type = 'REAL'"));
        assertThat(r.get("payers_topup")).isEqualTo((int) direct(
                "SELECT count(DISTINCT p.user_id) FROM payment_intents p JOIN users u ON u.id = p.user_id "
                + "WHERE p.purpose = 'PAWCOIN_TOPUP' AND p.status = 'PAID' AND p.updated_at >= ? AND p.updated_at < ? "
                + "AND u.role = 'USER' AND u.account_type = 'REAL'"));
        assertThat(r.get("payers_gift")).isEqualTo((int) direct(
                "SELECT count(DISTINCT g.sender_id) FROM gift_orders g JOIN users u ON u.id = g.sender_id "
                + "WHERE g.status IN ('DELIVERED', 'UNDELIVERED_REFUND_PENDING', 'UNDELIVERED_REFUNDED', 'REFUND_MANUAL') "
                + "AND COALESCE(g.delivered_at, g.created_at) >= ? AND COALESCE(g.delivered_at, g.created_at) < ? "
                + "AND u.role = 'USER' AND u.account_type = 'REAL'"));
        assertThat(r.get("new_users")).isEqualTo((int) direct(
                "SELECT count(*) FROM users u WHERE u.created_at >= ? AND u.created_at < ? "
                + "AND u.role = 'USER' AND u.account_type = 'REAL'"));

        // 构造集的期望值（X 是很早的日期，业务库在 X 没有别的数据）
        assertThat(r.get("toko_buyers")).isEqualTo(2);         // a（两笔算一人）、c（退款仍算）
        assertThat(r.get("payers_ktp")).isEqualTo(2);          // a QRIS、b PawCoin
        assertThat(r.get("payers_tailsonality")).isEqualTo(1); // b（a 的 ORPHAN_PAID 不算）
        assertThat(r.get("payers_passport")).isEqualTo(1);     // c（兽医不算）
        assertThat(r.get("payers_pet_slot")).isEqualTo(1);     // b（ADMIN_GRANT 不算）
        assertThat(r.get("payers_ai_unlock")).isEqualTo(1);    // c
        assertThat(r.get("payers_vet_consult")).isEqualTo(1);  // c（d 的是次日）
        assertThat(r.get("nontoko_payers")).isEqualTo(3);      // a、b、c 并集
        assertThat(r.get("payers_topup")).isEqualTo(1);        // a
        assertThat(r.get("payers_gift")).isEqualTo(0);
        assertThat(r.get("new_users")).isEqualTo(2);           // a、b（兽医 / 虚拟号 / 前一天 / 次日不算）
        assertThat(r.get("dau_source")).isEqualTo("GA4");
    }

    /** AC7：GA4 没拉到 → 分母 / 比率 / 回访 NULL、分子照写；补上快照后重跑填上。 */
    @Test
    void missingGa4DenominatorIsNullThenFilled() {
        long a = user(wib(x, 9, 0));
        shopOrder(a, "PENDING_SHIPMENT", wib(x, 10, 0));

        run("GA4");
        Map<String, Object> r = row();
        assertThat(r.get("dau")).isNull();
        assertThat(r.get("s4_ratio")).isNull();
        assertThat(r.get("p3_ratio")).isNull();
        assertThat(r.get("returning_users")).isNull();
        assertThat(r.get("toko_buyers")).isEqualTo(1);
        assertThat(r.get("new_users")).isEqualTo(1);
        assertThat(r.get("dau_source")).isEqualTo("GA4");

        analyticsJdbc.update("INSERT INTO ga4_daily_snapshot (stat_date, active_users_all, active_users_new_version, "
                + "min_app_version, fetched_at) VALUES (?, 8, 3, '1.5.0', now())", x);
        run("GA4");
        r = row();
        assertThat(r.get("dau")).isEqualTo(8);
        assertThat(new java.math.BigDecimal(r.get("s4_ratio").toString())).isEqualByComparingTo("0.125");
        assertThat(new java.math.BigDecimal(r.get("p3_ratio").toString())).isEqualByComparingTo("0");
        assertThat(r.get("returning_users")).isEqualTo(7);
    }

    /** AC2：SELF 取 agg_daily_activity 的 all 行（不取分平台行），并记来源；分母 0 → 比率 NULL。 */
    @Test
    void selfUsesActivityAllRow() {
        long a = user(wib(x, 9, 0));
        shopOrder(a, "PENDING_SHIPMENT", wib(x, 10, 0));
        analyticsJdbc.update("INSERT INTO ga4_daily_snapshot (stat_date, active_users_all, active_users_new_version, "
                + "min_app_version, fetched_at) VALUES (?, 99, 3, '1.5.0', now())", x);
        analyticsJdbc.update("INSERT INTO agg_daily_activity (stat_date, platform, dau_person, dau_logged_in, computed_at) "
                + "VALUES (?, 'all', 4, 2, now()), (?, 'android', 3, 1, now())", x, x);

        run("SELF");
        Map<String, Object> r = row();
        assertThat(r.get("dau_source")).isEqualTo("SELF");
        assertThat(r.get("dau")).isEqualTo(4);
        assertThat(new java.math.BigDecimal(r.get("s4_ratio").toString())).isEqualByComparingTo("0.25");
        assertThat(r.get("returning_users")).isEqualTo(3);

        analyticsJdbc.update("UPDATE agg_daily_activity SET dau_person = 0 WHERE stat_date = ? AND platform = 'all'", x);
        run("SELF");
        r = row();
        assertThat(r.get("dau")).isEqualTo(0);
        assertThat(r.get("s4_ratio")).isNull();
        assertThat(r.get("returning_users")).isEqualTo(-1);
    }
}
