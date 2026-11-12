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
    /** 每批用户数（{@code user_id = ANY(?)} 的数组长度上限）。 */
    static final int USER_BATCH = 1000;

    /** 运营推送活动（业务库一行）。 */
    public record Campaign(String token, Integer plannedCount, Integer recipientCount, String targetType,
            String targetValue, Instant sentAt) {
    }

    /** 已付款订单（订单 token 只用来去重计数，不落汇总表）。 */
    public record PaidOrder(long userId, String orderToken, Instant paidAt) {
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

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
