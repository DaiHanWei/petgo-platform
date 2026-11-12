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
 * L1：指标族 {@code product}（analytics-pipeline Story 4.6 AC4 / AC8），真业务库 + 埋点测试库 {@code petgo_analytics_test}。
 *
 * <ul>
 *   <li>隔离：本测试新建的商品（token 带唯一前缀）各自一行，只断言这些行。</li>
 *   <li>统计日 S = WIB 今天往前 3 天；{@code as_of} = WIB 昨天。</li>
 *   <li>AC8：{@code paid_qty_30d} 与直接查业务库同一窗口、同一商品一致；回补重算不覆盖已记录的库存快照。</li>
 * </ul>
 */
class ProductRollupIntegrationTest extends ApiIntegrationTest {

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
    private LocalDate s;
    private LocalDate asOf;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        long n = SEQ.incrementAndGet();
        prefix = "it-prod-" + n + "-";
        tok = "itp" + Long.toString(n % 1_000_000_000_000L, 36);
        LocalDate todayWib = EventTimeCorrector.wibDate(Instant.now());
        asOf = todayWib.minusDays(1);
        s = todayWib.minusDays(3);
        service = service();
        analyticsTx.executeWithoutResult(st -> {
            for (LocalDate d = s.minusDays(1); !d.isAfter(s.plusDays(1)); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + DetailPartitions.partitionName(d)
                        + " PARTITION OF " + DetailPartitions.PARENT + " FOR VALUES FROM ('" + d + "') TO ('"
                        + d.plusDays(1) + "')");
            }
        });
    }

    private RollupService service() {
        return new RollupService(state, analyticsJdbc, analyticsTx, businessJdbc,
                new RollupProgressRepository(analyticsJdbc), List.of(new ProductFamily(new BusinessPlacementReader(
                        businessJdbc))), AnalyticsRollupProperties.defaults(), mock(OpsAlertService.class), "test",
                Clock.systemUTC());
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM agg_daily_product WHERE product_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM shop_orders WHERE public_token LIKE ?", tok + "%");
        businessJdbc.update("DELETE FROM shop_products WHERE public_token LIKE ?", tok + "%");
    }

    // ---------------------------------------------------------------- 造数

    private static Instant wib(LocalDate d, int hour, int minute) {
        return d.atTime(hour, minute).atZone(EventTimeCorrector.WIB).toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    /** 商品 + 每个 (actual, locked) 一个 SKU；返回 {productToken, 第一个 skuId}。 */
    private String[] product(boolean active, long[]... inventories) {
        String p = tok + "p" + SEQ.incrementAndGet() % 1_000_000;
        businessJdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'IT Product', 'B', 'MAKANAN', 'img/key.jpg', 'DOG', '<p/>', 'n', 'RETURNABLE', ?)
                """, p, active);
        Long pid = businessJdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, p);
        Long first = null;
        for (long[] inv : inventories) {
            String sk = tok + "s" + SEQ.incrementAndGet() % 1_000_000;
            businessJdbc.update("INSERT INTO shop_skus (public_token, product_id, spec_name, price) "
                    + "VALUES (?, ?, '1 kg', 10000)", sk, pid);
            Long sid = businessJdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sk);
            businessJdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, ?)", sid, inv[0],
                    inv[1]);
            first = first == null ? sid : first;
        }
        return new String[] {p, String.valueOf(first)};
    }

    private void paidLine(long userId, String[] p, int qty, Instant paidAt, int refunded) {
        String token = tok + "o" + SEQ.incrementAndGet();
        businessJdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at, paid_at, checkout_source)
                VALUES (?, ?, ?, ?, 10000, 0, 0, 10000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', ?, ?, ?, 'BUY_NOW')
                """, token, "IT-PROD-" + SEQ.incrementAndGet() % 100_000_000, userId,
                paidAt == null ? "PENDING_PAYMENT" : "PENDING_SHIPMENT",
                utc(paidAt == null ? wib(s, 8, 0) : paidAt.minusSeconds(60)),
                utc(paidAt == null ? wib(s, 8, 0) : paidAt.minusSeconds(60)), paidAt == null ? null : utc(paidAt));
        Long oid = businessJdbc.queryForObject("SELECT id FROM shop_orders WHERE public_token = ?", Long.class, token);
        businessJdbc.update("""
                INSERT INTO shop_order_lines (order_id, sku_id, product_name, spec_name, unit_price, qty, line_total,
                        return_policy, refunded_qty)
                VALUES (?, ?, 'IT Product', '1 kg', 10000, ?, ?, 'RETURNABLE', ?)""", oid, Long.parseLong(p[1]), qty,
                10_000L * qty, refunded);
    }

    private void event(String event, Long userId, String productToken, boolean production) {
        Instant at = wib(s, 10, 0);
        analyticsJdbc.update("""
                INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, is_suspicious, source,
                                           anonymous_id, user_id, user_key, platform, is_production, properties)
                VALUES (?, ?, ?, ?, ?, false, 'APP', ?, ?, ?, 'android', ?, ?::jsonb)""",
                prefix + "det-" + SEQ.incrementAndGet(), event, utc(at), utc(at), s, UUID.randomUUID().toString(),
                userId, userId == null ? null : AnalyticsDistinctId.of(userId), production,
                "{\"product_token\":\"" + productToken + "\"}");
    }

    private void run() {
        assertThat(service.rerun(s, s, List.of(ProductFamily.NAME), asOf).outcome()).isEqualTo(Outcome.DONE);
    }

    private Map<String, Object> row(String[] p) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT * FROM agg_daily_product WHERE stat_date = ? AND product_token = ?", s, p[0]);
        assertThat(rows).as("%s %s", s, p[0]).hasSize(1);
        return rows.get(0);
    }

    private long directPaidQty(String productToken) {
        Long n = businessJdbc.queryForObject("""
                SELECT COALESCE(SUM(l.qty), 0) FROM shop_orders o JOIN users u ON u.id = o.user_id
                JOIN shop_order_lines l ON l.order_id = o.id JOIN shop_skus k ON k.id = l.sku_id
                JOIN shop_products p ON p.id = k.product_id
                WHERE p.public_token = ? AND o.paid_at >= ? AND o.paid_at < ?
                  AND u.role = 'USER' AND u.account_type = 'REAL'""", Long.class, productToken,
                utc(wib(s.minusDays(29), 0, 0)), utc(wib(s.plusDays(1), 0, 0)));
        return n == null ? 0 : n;
    }

    private static long n(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }

    // ---------------------------------------------------------------- 场景

    /** AC4：浏览 / 加购次数（排除非生产、兽医）；可售库存 = Σ max(actual − locked, 0)；售罄；下架商品也有行。 */
    @Test
    void countsStockAndSoldOut() {
        String[] hot = product(true, new long[] {5, 5}, new long[] {0, 0});
        String[] stocked = product(false, new long[] {7, 2}, new long[] {3, 0});
        long u = newUser().getId();
        long vet = newUser().getId();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", vet);
        event("toko_product_detail_viewed", u, hot[0], true);
        event("toko_product_detail_viewed", u, hot[0], true);
        event("toko_product_detail_viewed", null, hot[0], true);
        event("toko_product_detail_viewed", u, hot[0], false);
        event("toko_product_detail_viewed", vet, hot[0], true);
        event("toko_add_to_cart_tapped", u, hot[0], true);
        // 业务库查不到的 token（已删除商品）丢弃
        event("toko_product_detail_viewed", u, tok + "gone", true);
        run();
        Map<String, Object> r = row(hot);
        assertThat(n(r, "detail_views")).isEqualTo(3);
        assertThat(n(r, "add_to_cart")).isEqualTo(1);
        assertThat(n(r, "sellable_stock")).isZero();
        assertThat(r.get("is_sold_out")).isEqualTo(true);
        assertThat(r.get("is_active")).isEqualTo(true);
        assertThat(r.get("product_name")).isEqualTo("IT Product");
        Map<String, Object> r2 = row(stocked);
        assertThat(n(r2, "sellable_stock")).isEqualTo(8);
        assertThat(r2.get("is_sold_out")).isEqualTo(false);
        assertThat(r2.get("is_active")).isEqualTo(false);
        assertThat(n(r2, "detail_views")).isZero();
        Integer gone = analyticsJdbc.queryForObject(
                "SELECT count(*) FROM agg_daily_product WHERE product_token = ?", Integer.class, tok + "gone");
        assertThat(gone).isZero();
    }

    /** AC8：paid_qty_30d 与直接查业务库一致（窗口边界、退款仍算、未付款 / 兽医不算）；回补不覆盖库存快照。 */
    @Test
    void paidQtyMatchesBusinessAndSnapshotKept() {
        String[] p = product(true, new long[] {4, 1});
        long u = newUser().getId();
        long vet = newUser().getId();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", vet);
        paidLine(u, p, 2, wib(s, 9, 0), 0);
        paidLine(u, p, 3, wib(s.minusDays(29), 0, 0), 3);       // 窗口首日 0 点（含），全退仍算
        paidLine(u, p, 5, wib(s.minusDays(30), 23, 59), 0);     // 窗口之前
        paidLine(u, p, 7, wib(s.plusDays(1), 0, 0), 0);         // 窗口之后（不含）
        paidLine(u, p, 11, null, 0);                            // 未付款
        paidLine(vet, p, 13, wib(s, 9, 0), 0);                  // 兽医
        run();
        Map<String, Object> first = row(p);
        assertThat(n(first, "paid_qty_30d")).isEqualTo(5).isEqualTo(directPaidQty(p[0]));
        assertThat(n(first, "sellable_stock")).isEqualTo(3);

        // 库存变化 + 新的已付款订单 → 回补只更新计数与 paid_qty_30d
        businessJdbc.update("UPDATE sku_inventory SET actual = 0, locked = 0 WHERE sku_id = ?", Long.parseLong(p[1]));
        paidLine(u, p, 1, wib(s, 20, 0), 0);
        event("toko_product_detail_viewed", u, p[0], true);
        run();
        Map<String, Object> second = row(p);
        assertThat(n(second, "paid_qty_30d")).isEqualTo(6).isEqualTo(directPaidQty(p[0]));
        assertThat(n(second, "detail_views")).isEqualTo(1);
        assertThat(n(second, "sellable_stock")).isEqualTo(3);
        assertThat(second.get("is_sold_out")).isEqualTo(false);
        assertThat(second.get("stock_captured_at")).isEqualTo(first.get("stock_captured_at"));
    }
}
