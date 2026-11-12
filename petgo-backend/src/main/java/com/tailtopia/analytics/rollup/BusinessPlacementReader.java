package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.PlacementTokoRule.Target;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 业务库窄查询（analytics-pipeline Story 4.4 T2，D-13 / 架构「边界」）：只读、只取需要的列，返回 public token 与计数，
 * 在埋点库单元事务<b>之外</b>调用（{@link RollupFamily#prepareDay}）。两库之间不做跨库 SQL：结果经 Java 写进埋点库临时表。
 *
 * <p>用 {@code @Primary} 业务库 {@code JdbcTemplate}。🔒 不读任何 PII 列（{@code shop_orders} 有收货人姓名、电话、地址，绝不 {@code SELECT *}）；
 * 日志不打用户标识与订单 token。
 */
@Component
public class BusinessPlacementReader {

    /** 运营弹窗配置（target_value：APP_PAGE 时为页面枚举名）。 */
    static final String OPS_POPUPS_SQL = "SELECT public_token, target_type, target_value FROM ops_popups";
    /** Toko 横幅配置（横幅一律算推广 Toko，只用来判断 token 是否还在）。 */
    static final String SHOP_BANNERS_SQL = "SELECT public_token, target_type FROM shop_banners";
    /** 运营推送活动：「发送时间」口径与 OpsNotificationCampaignRepository 一致；待发送 / 已取消的不算。 */
    static final String CAMPAIGNS_SQL = "SELECT public_token, planned_count, recipient_count, target_type, target_value, "
            + "COALESCE(started_at, scheduled_at, created_at) AS sent_at FROM ops_notification_campaigns "
            + "WHERE status NOT IN ('PENDING', 'CANCELED')";
    /** 已付款 Toko 订单（D-13：paid_at 非空，退款 / 取消仍算）：按用户 + 付款时间区间，ix_shop_orders_user 先按用户缩小范围。 */
    static final String PAID_ORDERS_SQL = "SELECT user_id, public_token, paid_at FROM shop_orders "
            + "WHERE paid_at >= ? AND paid_at < ? AND user_id = ANY(?)";
    /**
     * Story 4.5（产品 #2 分子 / 电商 #1）：{@code paid_at} 落在区间内的已付款订单数，只算下单人 USER + REAL（D-13：退款 / 取消仍算）。
     * {@code paid_at} 只有带商家条件的部分索引，按时间区间会顺扫；订单量小、低峰跑，可接受（不为此加业务迁移）。
     */
    static final String PAID_ORDER_COUNT_SQL = "SELECT count(*) FROM shop_orders o JOIN users u ON u.id = o.user_id "
            + "WHERE o.paid_at >= ? AND o.paid_at < ? AND u.role = 'USER' AND u.account_type = 'REAL'";
    /** Story 4.5（参照列 orders_created_biz）：{@code created_at} 落在区间内的订单数，只算下单人 USER + REAL。 */
    static final String CREATED_ORDER_COUNT_SQL = "SELECT count(*) FROM shop_orders o JOIN users u ON u.id = o.user_id "
            + "WHERE o.created_at >= ? AND o.created_at < ? AND u.role = 'USER' AND u.account_type = 'REAL'";
    /**
     * Story 4.6（电商 #7）：{@code created_at} 落在区间内、{@code checkout_source = 'CART'} 的订单行（下单人 USER + REAL），
     * 带 SKU / 商品 public token 与 {@code paid_at}（D-13）。「立即购买」等其他来源不算购物车购买。
     */
    static final String CART_ORDER_LINES_SQL = "SELECT o.user_id, s.public_token, p.public_token, o.created_at, o.paid_at "
            + "FROM shop_orders o JOIN users u ON u.id = o.user_id "
            + "JOIN shop_order_lines l ON l.order_id = o.id "
            + "JOIN shop_skus s ON s.id = l.sku_id "
            + "JOIN shop_products p ON p.id = s.product_id "
            + "WHERE o.checkout_source = 'CART' AND o.created_at >= ? AND o.created_at < ? "
            + "AND u.role = 'USER' AND u.account_type = 'REAL'";
    /** Story 4.6（电商 #8 / #9）：商品全集（含下架，至少一个 SKU）与可售库存 Σ max(actual − locked, 0)（可售不落库）。 */
    static final String PRODUCT_SNAPSHOT_SQL = "SELECT p.public_token, p.name, p.is_active, "
            + "COALESCE(SUM(GREATEST(i.actual - i.locked, 0)), 0) AS sellable_stock "
            + "FROM shop_products p JOIN shop_skus s ON s.product_id = p.id "
            + "LEFT JOIN sku_inventory i ON i.sku_id = s.id "
            + "GROUP BY p.public_token, p.name, p.is_active";
    /** Story 4.6（电商 #9）：{@code paid_at} 落在区间内的已付款件数 Σ qty（按商品；下单人 USER + REAL；不扣退款，D-13）。 */
    static final String PAID_QTY_BY_PRODUCT_SQL = "SELECT p.public_token, SUM(l.qty) AS paid_qty "
            + "FROM shop_orders o JOIN users u ON u.id = o.user_id "
            + "JOIN shop_order_lines l ON l.order_id = o.id "
            + "JOIN shop_skus s ON s.id = l.sku_id "
            + "JOIN shop_products p ON p.id = s.product_id "
            + "WHERE o.paid_at >= ? AND o.paid_at < ? AND u.role = 'USER' AND u.account_type = 'REAL' "
            + "GROUP BY p.public_token";
    /** 每批用户数（{@code user_id = ANY(?)} 的数组长度上限）。 */
    static final int USER_BATCH = 1000;

    /** 运营推送活动（业务库一行）。 */
    public record Campaign(String token, Integer plannedCount, Integer recipientCount, String targetType,
            String targetValue, Instant sentAt) {
    }

    /** 已付款订单（订单 token 只用来去重计数，不落汇总表）。 */
    public record PaidOrder(long userId, String orderToken, Instant paidAt) {
    }

    /** 购物车订单行（Story 4.6；user_id 只在埋点库临时表里与明细匹配，不落汇总表）。paidAt 为空 = 未付款。 */
    public record CartOrderLine(long userId, String skuToken, String productToken, Instant createdAt, Instant paidAt) {
    }

    /** 商品快照（Story 4.6）：业务库此刻的名称、上下架与可售库存。 */
    public record ProductStock(String productToken, String name, boolean active, long sellableStock) {
    }

    private final JdbcTemplate jdbc;

    public BusinessPlacementReader(JdbcTemplate businessJdbcTemplate) {
        this.jdbc = businessJdbcTemplate;
    }

    /** 运营弹窗：token → 跳转目标。 */
    public Map<String, Target> opsPopupTargets() {
        Map<String, Target> out = new HashMap<>();
        jdbc.query(OPS_POPUPS_SQL, rs -> {
            out.put(rs.getString(1), new Target(rs.getString(2), rs.getString(3)));
        });
        return out;
    }

    /** Toko 横幅：token → 跳转目标（只有类型）。 */
    public Map<String, Target> shopBannerTargets() {
        Map<String, Target> out = new HashMap<>();
        jdbc.query(SHOP_BANNERS_SQL, rs -> {
            out.put(rs.getString(1), new Target(rs.getString(2), null));
        });
        return out;
    }

    /** 已开始发送的运营推送活动。 */
    public List<Campaign> campaigns() {
        return jdbc.query(CAMPAIGNS_SQL, (rs, i) -> new Campaign(rs.getString(1), rs.getObject(2, Integer.class),
                rs.getObject(3, Integer.class), rs.getString(4), rs.getString(5), toInstant(rs.getTimestamp(6))));
    }

    /**
     * 这些用户在 {@code [from, toExclusive)} 内付款的订单（分批，每批 {@link #USER_BATCH} 个用户）。
     * 用户为空 → 不查库。
     */
    public List<PaidOrder> paidOrders(Collection<Long> userIds, Instant from, Instant toExclusive) {
        List<PaidOrder> out = new ArrayList<>();
        if (userIds.isEmpty() || !from.isBefore(toExclusive)) {
            return out;
        }
        List<Long> ids = List.copyOf(userIds);
        OffsetDateTime f = OffsetDateTime.ofInstant(from, ZoneOffset.UTC);
        OffsetDateTime t = OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC);
        for (int i = 0; i < ids.size(); i += USER_BATCH) {
            Long[] batch = ids.subList(i, Math.min(ids.size(), i + USER_BATCH)).toArray(Long[]::new);
            jdbc.query(PAID_ORDERS_SQL, ps -> {
                ps.setObject(1, f);
                ps.setObject(2, t);
                Array arr = ps.getConnection().createArrayOf("bigint", batch);
                ps.setArray(3, arr);
            }, rs -> {
                out.add(new PaidOrder(rs.getLong(1), rs.getString(2), toInstant(rs.getTimestamp(3))));
            });
        }
        return out;
    }

    /** Story 4.5：{@code [from, toExclusive)} 内付款的订单数（下单人 USER + REAL）。 */
    public long paidOrderCount(Instant from, Instant toExclusive) {
        return count(PAID_ORDER_COUNT_SQL, from, toExclusive);
    }

    /** Story 4.5：{@code [from, toExclusive)} 内创建的订单数（下单人 USER + REAL）。 */
    public long createdOrderCount(Instant from, Instant toExclusive) {
        return count(CREATED_ORDER_COUNT_SQL, from, toExclusive);
    }

    /** Story 4.6：{@code [from, toExclusive)} 内创建的购物车订单行（下单人 USER + REAL）。 */
    public List<CartOrderLine> cartOrderLines(Instant from, Instant toExclusive) {
        if (!from.isBefore(toExclusive)) {
            return List.of();
        }
        return jdbc.query(CART_ORDER_LINES_SQL, (rs, i) -> new CartOrderLine(rs.getLong(1), rs.getString(2),
                rs.getString(3), toInstant(rs.getTimestamp(4)), toInstant(rs.getTimestamp(5))),
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC), OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
    }

    /** Story 4.6：业务库全部商品（含下架）的当前名称、上下架与可售库存。 */
    public List<ProductStock> productStocks() {
        return jdbc.query(PRODUCT_SNAPSHOT_SQL, (rs, i) -> new ProductStock(rs.getString(1), rs.getString(2),
                rs.getBoolean(3), rs.getLong(4)));
    }

    /** Story 4.6：{@code [from, toExclusive)} 内付款的件数，商品 token → Σ qty（下单人 USER + REAL）。 */
    public Map<String, Long> paidQtyByProduct(Instant from, Instant toExclusive) {
        Map<String, Long> out = new HashMap<>();
        if (!from.isBefore(toExclusive)) {
            return out;
        }
        jdbc.query(PAID_QTY_BY_PRODUCT_SQL, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        }, OffsetDateTime.ofInstant(from, ZoneOffset.UTC), OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
        return out;
    }

    private long count(String sql, Instant from, Instant toExclusive) {
        if (!from.isBefore(toExclusive)) {
            return 0;
        }
        Long n = jdbc.queryForObject(sql, Long.class, OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
        return n == null ? 0 : n;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
