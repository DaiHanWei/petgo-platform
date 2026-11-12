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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * L1：指标族 {@code cart}（analytics-pipeline Story 4.6 AC1 / AC2 / AC7），真业务库 + 埋点测试库 {@code petgo_analytics_test}
 * （前置同 {@code RollupActivityInstallIntegrationTest}）。
 *
 * <ul>
 *   <li>隔离：每个场景用本测试新建的商品 / SKU（token 带唯一前缀），断言该商品的 {@code PRODUCT} 行（全库共用的 TOTAL 行用增量断言）。</li>
 *   <li>加购日 X = WIB 今天往前 20 天；{@code as_of} = WIB 昨天（X 的 7 天观察窗已关闭，{@code is_final = true}）。</li>
 *   <li>明细 / 关联按 {@code message_id} 前缀清理；业务库订单、商品按 token 前缀清理；汇总行按商品 token 前缀清理。</li>
 * </ul>
 */
class CartRollupIntegrationTest extends ApiIntegrationTest {

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
        service = new RollupService(state, analyticsJdbc, analyticsTx, businessJdbc,
                new RollupProgressRepository(analyticsJdbc), List.of(new CartFamily(reader)),
                AnalyticsRollupProperties.defaults(), mock(OpsAlertService.class), "test", Clock.systemUTC());
        long n = SEQ.incrementAndGet();
        prefix = "it-cart-" + n + "-";
        tok = "itc" + Long.toString(n % 1_000_000_000_000L, 36);
        LocalDate todayWib = EventTimeCorrector.wibDate(Instant.now());
        asOf = todayWib.minusDays(1);
        x = todayWib.minusDays(20);
        partitions(x.minusDays(1), x.plusDays(9));
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM identity_links WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM agg_daily_cart WHERE product_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM shop_orders WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM shop_products WHERE public_token LIKE ?", tok + "%");
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

    /** 一个商品 + 一个 SKU；返回 {productToken, skuToken, skuId}。 */
    private String[] product() {
        String p = tok + "p" + SEQ.incrementAndGet() % 1_000_000;
        businessJdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'IT Cart', 'B', 'MAKANAN', 'img/key.jpg', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, p);
        Long pid = businessJdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, p);
        String s = tok + "s" + SEQ.incrementAndGet() % 1_000_000;
        businessJdbc.update("INSERT INTO shop_skus (public_token, product_id, spec_name, price) VALUES (?, ?, '1 kg', 10000)",
                s, pid);
        Long sid = businessJdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, s);
        businessJdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 10, 0)", sid);
        return new String[] {p, s, String.valueOf(sid)};
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

    private void add(String[] p, String anon, Long userId, LocalDate day, int hour, int minute, int qty, long price) {
        event("toko_add_to_cart_tapped", anon, userId, day, hour, minute, true, false,
                "{\"product_token\":\"" + p[0] + "\",\"sku_token\":\"" + p[1] + "\",\"qty\":" + qty
                        + ",\"unit_price\":" + price + "}");
    }

    private void removed(String[] p, String anon, Long userId, LocalDate day, int hour, int minute, String reason) {
        event("toko_cart_item_removed", anon, userId, day, hour, minute, true, false,
                "{\"product_token\":\"" + p[0] + "\",\"sku_token\":\"" + p[1] + "\",\"qty\":1,\"unit_price\":1,"
                        + "\"reason\":\"" + reason + "\"}");
    }

    private void qtyChanged(String[] p, String anon, Long userId, LocalDate day, int hour, int minute, int before,
            int after) {
        event("toko_cart_quantity_changed", anon, userId, day, hour, minute, true, false,
                "{\"product_token\":\"" + p[0] + "\",\"sku_token\":\"" + p[1] + "\",\"qty_before\":" + before
                        + ",\"qty_after\":" + after + "}");
    }

    private long vetUser() {
        long id = newUser().getId();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", id);
        return id;
    }

    /** 订单 + 一行（该 SKU）；paidAt 为空 = 未付款。 */
    private void order(long userId, String source, String[] p, Instant createdAt, Instant paidAt) {
        String token = tok + "o" + SEQ.incrementAndGet();
        businessJdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at, paid_at, checkout_source)
                VALUES (?, ?, ?, ?, 10000, 0, 0, 10000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', ?, ?, ?, ?)
                """, token, "IT-CART-" + SEQ.incrementAndGet() % 100_000_000, userId,
                paidAt == null ? "PENDING_PAYMENT" : "PENDING_SHIPMENT", utc(createdAt), utc(createdAt),
                paidAt == null ? null : utc(paidAt), source);
        Long oid = businessJdbc.queryForObject("SELECT id FROM shop_orders WHERE public_token = ?", Long.class, token);
        businessJdbc.update("""
                INSERT INTO shop_order_lines (order_id, sku_id, product_name, spec_name, unit_price, qty, line_total,
                        return_policy)
                VALUES (?, ?, 'IT Cart', '1 kg', 10000, 1, 10000, 'RETURNABLE')""", oid, Long.parseLong(p[2]));
    }

    // ---------------------------------------------------------------- 运行与读取

    private void run(LocalDate from, LocalDate to) {
        assertThat(service.rerun(from, to, List.of(CartFamily.NAME), asOf).outcome()).isEqualTo(Outcome.DONE);
    }

    private Map<String, Object> productRow(LocalDate day, String[] p) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT * FROM agg_daily_cart WHERE stat_date = ? AND scope = 'PRODUCT' AND product_token = ?", day, p[0]);
        assertThat(rows).as("%s %s", day, p[0]).hasSize(1);
        return rows.get(0);
    }

    private boolean hasRow(LocalDate day, String[] p) {
        Integer n = analyticsJdbc.queryForObject(
                "SELECT count(*) FROM agg_daily_cart WHERE stat_date = ? AND product_token = ?", Integer.class, day,
                p[0]);
        return n != null && n > 0;
    }

    private Map<String, Object> totalRow(LocalDate day) {
        return analyticsJdbc.queryForMap("SELECT * FROM agg_daily_cart WHERE stat_date = ? AND scope = 'TOTAL'", day);
    }

    private static long n(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? -1 : ((Number) v).longValue();
    }

    // ---------------------------------------------------------------- 场景

    /** AC7：加购 → 改数量 → 移出 记为移出；数量取最后一次 qty_after；同一天重复加购只算一件。 */
    @Test
    void addChangeRemoveIsRemoved() {
        String[] p = product();
        long u = newUser().getId();
        String a = anon();
        add(p, a, u, x, 10, 0, 2, 10_000);
        add(p, a, u, x, 10, 5, 1, 10_000);
        qtyChanged(p, a, u, x, 11, 0, 2, 3);
        removed(p, a, u, x, 12, 0, "manual");
        run(x, x);
        Map<String, Object> r = productRow(x, p);
        assertThat(n(r, "add_events")).isEqualTo(2);
        assertThat(n(r, "add_items")).isEqualTo(1);
        assertThat(n(r, "removed_items")).isEqualTo(1);
        assertThat(n(r, "removed_manual")).isEqualTo(1);
        assertThat(n(r, "in_cart_items")).isZero();
        // 停留 120 分钟 → [1 小时, 1 天)
        assertThat(n(r, "dwell_1h_1d")).isEqualTo(1);
        assertThat(n(r, "dwell_p50_minutes")).isEqualTo(120);
        assertThat(n(r, "cart_value_idr")).isEqualTo(30_000);
        assertThat(r.get("carts")).isNull();
        assertThat(r.get("is_final")).isEqualTo(true);
    }

    /** AC7：加购 → 购物车下单并付款 记为已购买；下单未付款单列；「立即购买」同 SKU 不算购物车购买。 */
    @Test
    void purchasedUnpaidAndBuyNow() {
        String[] paid = product();
        long u1 = newUser().getId();
        add(paid, anon(), u1, x, 10, 0, 1, 50_000);
        order(u1, "CART", paid, wib(x, 10, 30), wib(x, 10, 40));

        String[] unpaid = product();
        long u2 = newUser().getId();
        add(unpaid, anon(), u2, x, 9, 0, 1, 10_000);
        order(u2, "CART", unpaid, wib(x.plusDays(2), 9, 0), null);

        String[] buyNow = product();
        long u3 = newUser().getId();
        add(buyNow, anon(), u3, x, 10, 0, 1, 10_000);
        order(u3, "BUY_NOW", buyNow, wib(x, 11, 0), wib(x, 11, 5));

        // 订单早于加购：不算
        String[] before = product();
        long u4 = newUser().getId();
        order(u4, "CART", before, wib(x, 8, 0), wib(x, 8, 5));
        add(before, anon(), u4, x, 10, 0, 1, 10_000);

        run(x, x);
        Map<String, Object> rp = productRow(x, paid);
        assertThat(n(rp, "purchased_items")).isEqualTo(1);
        assertThat(n(rp, "dwell_lt_1h")).isEqualTo(1);
        assertThat(n(rp, "dwell_p50_minutes")).isEqualTo(30);
        Map<String, Object> ru = productRow(x, unpaid);
        assertThat(n(ru, "ordered_unpaid_items")).isEqualTo(1);
        assertThat(n(ru, "purchased_items")).isZero();
        assertThat(n(ru, "dwell_1d_3d")).isEqualTo(1);
        Map<String, Object> rb = productRow(x, buyNow);
        assertThat(n(rb, "purchased_items")).isZero();
        assertThat(n(rb, "in_cart_items")).isEqualTo(1);
        assertThat(productRow(x, before).get("in_cart_items")).isEqualTo(1);
    }

    /** AC7：加购后 7 天无动作 记为仍在车里（不入分桶，中位数截到窗口结束）；第 8 天的移出不算。 */
    @Test
    void noActionWithinSevenDaysIsInCart() {
        String[] p = product();
        long u = newUser().getId();
        String a = anon();
        add(p, a, u, x, 0, 0, 1, 10_000);
        removed(p, a, u, x.plusDays(8), 0, 30, "manual");
        run(x, x);
        Map<String, Object> r = productRow(x, p);
        assertThat(n(r, "in_cart_items")).isEqualTo(1);
        assertThat(n(r, "removed_items")).isZero();
        assertThat(n(r, "dwell_lt_1h") + n(r, "dwell_1h_1d") + n(r, "dwell_1d_3d") + n(r, "dwell_3d_7d")).isZero();
        // 加购日 0 点 → 加购日 + 8 天 0 点 = 8 天
        assertThat(n(r, "dwell_p50_minutes")).isEqualTo(8 * 24 * 60);
    }

    /** AC7：跨午夜按 WIB 归日；兽医 / 非生产 / 可疑被排除。 */
    @Test
    void wibDayAndExclusions() {
        String[] p = product();
        add(p, anon(), newUser().getId(), x, 23, 30, 1, 10_000);
        add(p, anon(), newUser().getId(), x.plusDays(1), 0, 30, 1, 10_000);
        String[] ex = product();
        event("toko_add_to_cart_tapped", anon(), newUser().getId(), x, 9, 0, false, false,
                "{\"product_token\":\"" + ex[0] + "\",\"sku_token\":\"" + ex[1] + "\",\"qty\":1,\"unit_price\":1}");
        event("toko_add_to_cart_tapped", anon(), newUser().getId(), x, 9, 0, true, true,
                "{\"product_token\":\"" + ex[0] + "\",\"sku_token\":\"" + ex[1] + "\",\"qty\":1,\"unit_price\":1}");
        add(ex, anon(), vetUser(), x, 9, 0, 1, 10_000);
        run(x, x.plusDays(1));
        assertThat(n(productRow(x, p), "add_items")).isEqualTo(1);
        assertThat(n(productRow(x.plusDays(1), p), "add_items")).isEqualTo(1);
        assertThat(hasRow(x, ex)).isFalse();
    }

    /** Story 3.3 复审 R1：移出只带 sku_token（缺 product_token / qty / unit_price）也能匹配；reason = invalid_cleared。 */
    @Test
    void removalWithOnlySkuToken() {
        String[] p = product();
        long u = newUser().getId();
        String a = anon();
        add(p, a, u, x, 10, 0, 2, 7_000);
        event("toko_cart_item_removed", a, u, x.plusDays(3), 10, 0, true, false,
                "{\"sku_token\":\"" + p[1] + "\",\"reason\":\"invalid_cleared\"}");
        // 加购事件缺 product_token：取同 SKU 的订单行 / 其他事件补
        String[] q = product();
        long u2 = newUser().getId();
        event("toko_add_to_cart_tapped", anon(), u2, x, 10, 0, true, false,
                "{\"sku_token\":\"" + q[1] + "\",\"qty\":\"bad\"}");
        order(u2, "CART", q, wib(x, 11, 0), wib(x, 11, 10));
        run(x, x);
        Map<String, Object> r = productRow(x, p);
        assertThat(n(r, "removed_invalid")).isEqualTo(1);
        assertThat(n(r, "dwell_3d_7d")).isEqualTo(1);
        assertThat(n(r, "cart_value_idr")).isEqualTo(14_000);
        Map<String, Object> rq = productRow(x, q);
        assertThat(n(rq, "purchased_items")).isEqualTo(1);
        // 坏 qty 当缺失按 1，缺单价按 0
        assertThat(n(rq, "cart_value_idr")).isZero();
    }

    /** TOTAL 行：购物车数（去重人）、每车商品数、匿名人的件；重跑幂等。 */
    @Test
    void totalRowAndIdempotent() {
        LocalDate day = x.plusDays(2);
        run(day, day);
        Map<String, Object> before = totalRow(day);
        String[] p1 = product();
        String[] p2 = product();
        long u = newUser().getId();
        String a = anon();
        add(p1, a, u, day, 10, 0, 1, 10_000);
        add(p2, a, u, day, 10, 1, 2, 5_000);
        add(p1, anon(), null, day, 11, 0, 1, 10_000);
        run(day, day);
        Map<String, Object> after = totalRow(day);
        assertThat(n(after, "add_items") - n(before, "add_items")).isEqualTo(3);
        assertThat(n(after, "carts") - n(before, "carts")).isEqualTo(2);
        assertThat(n(after, "cart_value_idr") - n(before, "cart_value_idr")).isEqualTo(30_000);
        assertThat(after.get("product_token")).isEqualTo("");
        assertThat(n(after, "carts")).isPositive();
        assertThat((BigDecimal) after.get("items_per_cart_avg")).isEqualByComparingTo(BigDecimal
                .valueOf(n(after, "add_items")).divide(BigDecimal.valueOf(n(after, "carts")), 2,
                        java.math.RoundingMode.HALF_UP));
        Map<String, Object> r1 = productRow(day, p1);
        assertThat(n(r1, "add_items")).isEqualTo(2);
        assertThat(n(r1, "in_cart_items")).isEqualTo(2);
        run(day, day);
        assertThat(productRow(day, p1)).usingRecursiveComparison().ignoringFields("computed_at").isEqualTo(r1);
        assertThat(totalRow(day).get("add_items")).isEqualTo(after.get("add_items"));
    }

    /** 复审 R1：伪造的超大单价 × 数量（单件不溢出、求和超 bigint）不让汇总失败，价值封顶。 */
    @Test
    void forgedHugeValuesDoNotBreakRollup() {
        LocalDate day = x.plusDays(3);
        String[] p = product();
        String a = anon();
        for (int k = 0; k < 12; k++) {
            event("toko_add_to_cart_tapped", a, null, day, 10, k, true, false, "{\"product_token\":\"" + p[0]
                    + "\",\"sku_token\":\"" + p[1] + "-f" + k + "\",\"qty\":999999,\"unit_price\":999999999999}");
        }
        run(day, day);
        assertThat(n(productRow(day, p), "cart_value_idr")).isEqualTo(Long.MAX_VALUE);
        assertThat(n(totalRow(day), "cart_value_idr")).isEqualTo(Long.MAX_VALUE);
    }

    /** 观察窗未关闭的日期 is_final = false。 */
    @Test
    void openWindowIsNotFinal() {
        String[] p = product();
        LocalDate recent = asOf.minusDays(2);
        partitions(recent, asOf.plusDays(1));
        add(p, anon(), newUser().getId(), recent, 10, 0, 1, 10_000);
        run(recent, recent);
        assertThat(productRow(recent, p).get("is_final")).isEqualTo(false);
    }
}
