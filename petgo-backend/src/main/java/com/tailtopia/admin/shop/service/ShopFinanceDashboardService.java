package com.tailtopia.admin.shop.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经营数据（Story 8.1 销售与毛利 AB-13A · 8.2 库存周转 AB-13C · 8.3 对账 AB-13D）。
 *
 * <p>🔴 <b>数据源一律为业务库，不是 PostHog</b>（8.3 AC 明写）。财务口径不能建立在
 * 带丢失率与广告拦截偏差的三方 SaaS 上。
 *
 * <p>🔒 本类产出的<b>全部</b>是商业敏感数据（进货价、毛利、现金流）——
 * 调用方必须持 {@code shop.finance_view}（毛利/对账）或 {@code shop.cost_view}（进货价），
 * 且<b>两者都不默认授予既有运营角色</b>（NFR-11）。
 */
@Service
public class ShopFinanceDashboardService {

    private final JdbcTemplate jdbc;

    public ShopFinanceDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 8.1 销售与毛利（AB-13A） ----------

    /**
     * 销售与毛利。
     *
     * <p>🔴 <b>SPEC-22 的三行财务指标一并给出</b>：运费收入 / 承运成本 / 退款手续费。
     * 缺了承运成本，<b>假设 A-19 的验证方式就不可执行</b>（它写「按月核对实际承运成本与
     * 收取运费的差额」）—— 而 A-19 承接的正是 FR-99「省掉整条 API 对接工期」的全部风险。
     *
     * @param category 可空 = 不按品类下钻
     */
    @Transactional(readOnly = true)
    public MarginSnapshot margin(LocalDate from, LocalDate to, String category) {
        java.sql.Timestamp f = ts(from);
        java.sql.Timestamp t = tsEnd(to);
        String catFilter = category == null || category.isBlank() ? "" : " AND p.category = ? ";
        Object[] args = category == null || category.isBlank()
                ? new Object[] {f, t} : new Object[] {f, t, category};

        // 商品销售额与成本（成本按下单时那一刻的进货价快照 —— 现在的进货价算不出当时的毛利）
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COALESCE(SUM(l.line_total), 0) AS revenue,
                       COALESCE(SUM(COALESCE(s.cost_price, 0) * l.qty), 0) AS cost,
                       COALESCE(SUM(l.refunded_qty * l.unit_price), 0) AS refunded
                FROM shop_order_lines l
                JOIN shop_orders o ON o.id = l.order_id
                JOIN shop_skus s ON s.id = l.sku_id
                JOIN shop_products p ON p.id = s.product_id
                WHERE o.created_at >= ? AND o.created_at < ?
                  AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')
                """ + catFilter, args);

        long revenue = num(row.get("revenue"));
        long cost = num(row.get("cost"));
        long refunded = num(row.get("refunded"));

        // 🔴 SPEC-22 三行
        long shippingRevenue = scalar("""
                SELECT COALESCE(SUM(o.shipping_fee + o.shipping_discount), 0) FROM shop_orders o
                WHERE o.created_at >= ? AND o.created_at < ?
                  AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')
                """, f, t);
        long carrierCost = scalar("""
                SELECT COALESCE(SUM(sh.carrier_cost), 0) FROM shipments sh
                WHERE sh.shipped_at >= ? AND sh.shipped_at < ?
                """, f, t);
        long refundChannelFee = scalar("""
                SELECT COALESCE(SUM(COALESCE(r.payout_channel_fee, 0)), 0) FROM return_requests r
                WHERE r.refunded_at >= ? AND r.refunded_at < ?
                """, f, t);
        // 售后成本：补偿溢价 + 激励溢价 + 平台承担的回程运费
        long afterSalesCost = scalar("""
                SELECT COALESCE(SUM(r.compensation_premium + r.incentive_premium
                                    + r.shipback_reimbursed), 0)
                FROM return_requests r
                WHERE r.refunded_at >= ? AND r.refunded_at < ?
                """, f, t);

        List<Map<String, Object>> byReturnType = jdbc.queryForList("""
                SELECT r.return_type AS return_type,
                       COALESCE(SUM(r.compensation_premium + r.incentive_premium
                                    + r.shipback_reimbursed), 0) AS cost
                FROM return_requests r
                WHERE r.refunded_at >= ? AND r.refunded_at < ?
                GROUP BY r.return_type
                """, f, t);

        return new MarginSnapshot(revenue, cost, refunded, shippingRevenue, carrierCost,
                refundChannelFee, afterSalesCost, byReturnType);
    }

    /** 按 SKU 下钻。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> marginBySku(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT p.name AS product_name, s.spec_name AS spec_name,
                       SUM(l.qty) AS qty,
                       SUM(l.line_total) AS revenue,
                       SUM(COALESCE(s.cost_price, 0) * l.qty) AS cost
                FROM shop_order_lines l
                JOIN shop_orders o ON o.id = l.order_id
                JOIN shop_skus s ON s.id = l.sku_id
                JOIN shop_products p ON p.id = s.product_id
                WHERE o.created_at >= ? AND o.created_at < ?
                  AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')
                GROUP BY p.name, s.spec_name
                ORDER BY SUM(l.line_total) DESC
                """, ts(from), tsEnd(to));
    }

    // ---------- 8.2 库存周转与滞销（AB-13C） ----------

    /**
     * 库存周转与滞销。
     *
     * <p>🔴 <b>库存金额按进货价</b> —— 那是<b>资金占用的直接读数</b>（DEP-9 的监控依据）。
     * 按售价算会把还没赚到的毛利也算成占用的钱。
     *
     * <p>⚠️ <b>缺货损失只给服务端可得的近似</b>：AC 写的是 {@code out_of_stock_viewed}
     * 事件量，那是<b>客户端事件</b>，在 PostHog 里。这里给「当前售罄 SKU 数」作为
     * 服务端侧的替代读数，页面会写明两者不是一回事 —— 把它标成「缺货损失」是误导。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> inventoryTurnover(int staleDays) {
        return jdbc.queryForList("""
                SELECT p.name AS product_name, s.spec_name AS spec_name,
                       s.public_token AS sku_token,
                       i.actual AS actual, i.locked AS locked,
                       COALESCE(s.cost_price, 0) * i.actual AS stock_value,
                       (SELECT COALESCE(SUM(l.qty), 0) FROM shop_order_lines l
                        JOIN shop_orders o ON o.id = l.order_id
                        WHERE l.sku_id = s.id AND o.created_at >= now() - make_interval(days => ?)
                          AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')) AS sold_recent,
                       (SELECT MAX(o.created_at) FROM shop_order_lines l
                        JOIN shop_orders o ON o.id = l.order_id
                        WHERE l.sku_id = s.id
                          AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')) AS last_sold_at
                FROM shop_skus s
                JOIN shop_products p ON p.id = s.product_id
                LEFT JOIN sku_inventory i ON i.sku_id = s.id
                ORDER BY COALESCE(s.cost_price, 0) * COALESCE(i.actual, 0) DESC
                """, staleDays);
    }

    /** 当前售罄 SKU 数（缺货损失的服务端近似读数）。 */
    @Transactional(readOnly = true)
    public long outOfStockSkuCount() {
        return scalar("""
                SELECT COUNT(*) FROM sku_inventory i
                JOIN shop_skus s ON s.id = i.sku_id
                JOIN shop_products p ON p.id = s.product_id
                WHERE p.is_active = true AND (i.actual - i.locked) <= 0
                """);
    }

    // ---------- 8.3 对账（AB-13D） ----------

    /**
     * 对账。
     *
     * <p>🔴 <b>PawCoin 段与 QRIS 段必须拆分</b>。
     *
     * <p>🔴🔴 <b>被 PawCoin 抵扣的运费须单独可拆</b>（FR-100A 规则 3）——
     * 运费是平台向承运商支付的<b>真实现金支出</b>，用 PawCoin 覆盖等于<b>用预收款抵现金成本</b>。
     * 不拆则现金流量表失真：账面上看着收支平衡，实际现金在净流出。
     */
    @Transactional(readOnly = true)
    public ReconciliationSnapshot reconciliation(LocalDate from, LocalDate to) {
        java.sql.Timestamp f = ts(from);
        java.sql.Timestamp t = tsEnd(to);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COALESCE(SUM(o.total_amount), 0) AS total,
                       COALESCE(SUM(COALESCE(o.coin_amount, 0)), 0) AS coin,
                       COALESCE(SUM(COALESCE(o.cash_amount, o.total_amount)), 0) AS cash,
                       COALESCE(SUM(o.shipping_fee + o.shipping_discount), 0) AS shipping,
                       COUNT(*) AS orders
                FROM shop_orders o
                WHERE o.created_at >= ? AND o.created_at < ?
                  AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')
                """, f, t);

        long total = num(row.get("total"));
        long coin = num(row.get("coin"));
        long cash = num(row.get("cash"));
        long shippingCharged = num(row.get("shipping"));
        long orders = num(row.get("orders"));

        // 🔴 被 PawCoin 抵扣的运费：按该订单的 Coin 占比分摊到运费上。
        //    整数运算，逐单累加后取和 —— 先求和再乘比例会在多单场景下多丢一次余数。
        long shippingPaidByCoin = scalar("""
                SELECT COALESCE(SUM(
                    CASE WHEN o.total_amount > 0
                         THEN (o.shipping_fee + o.shipping_discount)
                              * COALESCE(o.coin_amount, 0) / o.total_amount
                         ELSE 0 END), 0)
                FROM shop_orders o
                WHERE o.created_at >= ? AND o.created_at < ?
                  AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')
                """, f, t);

        long refundedTotal = scalar("""
                SELECT COALESCE(SUM(o.refunded_total), 0) FROM shop_orders o
                WHERE o.created_at >= ? AND o.created_at < ?
                """, f, t);
        long refundedCoin = scalar("""
                SELECT COALESCE(SUM(o.refunded_coin), 0) FROM shop_orders o
                WHERE o.created_at >= ? AND o.created_at < ?
                """, f, t);

        // ⚠️ S-12 折中：不做钱包侧批次分层，给一行「赠币核销额（近似）」
        long bonusIssued = scalar("""
                SELECT COALESCE(SUM(delta), 0) FROM pawcoin_transactions
                WHERE type = 'BONUS' AND created_at >= ? AND created_at < ?
                """, f, t);
        long coinSpent = scalar("""
                SELECT COALESCE(SUM(-delta), 0) FROM pawcoin_transactions
                WHERE type = 'SPEND' AND created_at >= ? AND created_at < ?
                """, f, t);
        long bonusIssuedAllTime = scalar(
                "SELECT COALESCE(SUM(delta), 0) FROM pawcoin_transactions WHERE type = 'BONUS'");
        long creditedAllTime = scalar("""
                SELECT COALESCE(SUM(delta), 0) FROM pawcoin_transactions
                WHERE type IN ('TOPUP', 'BONUS', 'REFUND')
                """);
        // 近似核销率 = 历史赠币 ÷ 历史入账总额。⚠️ 这是【近似】，不是分层后的真值。
        long bonusRedeemedApprox = creditedAllTime <= 0 ? 0
                : coinSpent * bonusIssuedAllTime / creditedAllTime;

        return new ReconciliationSnapshot(orders, total, coin, cash, shippingCharged,
                shippingPaidByCoin, refundedTotal, refundedCoin, bonusIssued, coinSpent,
                bonusRedeemedApprox);
    }

    // ---------- 内部 ----------

    private long scalar(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static java.sql.Timestamp ts(LocalDate d) {
        return java.sql.Timestamp.valueOf(d.atStartOfDay());
    }

    private static java.sql.Timestamp tsEnd(LocalDate d) {
        return java.sql.Timestamp.valueOf(d.plusDays(1).atStartOfDay());
    }

    /**
     * 销售与毛利快照。
     *
     * @param carrierCost 🔴 S-11 承运成本 —— 没有它，假设 A-19 的验证方式不可执行
     */
    public record MarginSnapshot(long revenue, long cost, long refundedGoods,
            long shippingRevenue, long carrierCost, long refundChannelFee, long afterSalesCost,
            List<Map<String, Object>> afterSalesByReturnType) {

        public long grossProfit() {
            return revenue - cost;
        }

        /** 毛利率（%）。销售额为 0 时返回 0 —— 分母为零时任何比率都是编的。 */
        public double grossMarginPercent() {
            return revenue == 0 ? 0 : (double) grossProfit() * 100 / revenue;
        }

        /** 🔴 A-19 的直接读数：收取的运费 − 实际承运成本。为负说明运费在补贴配送。 */
        public long shippingNet() {
            return shippingRevenue - carrierCost;
        }

        /** 净额 = 销售额 − 退款商品额。 */
        public long netRevenue() {
            return revenue - refundedGoods;
        }
    }

    /**
     * 对账快照。
     *
     * @param shippingPaidByCoin 🔴 <b>被 PawCoin 抵扣的运费</b> ——
     *     它是「用预收款抵了真实现金支出」的金额，不单列出来现金流量表就会失真
     * @param bonusRedeemedApprox ⚠️ <b>近似值</b>（S-12）：本版本不做钱包侧批次分层。
     *     须财务确认这个精度够不够用；不够用则要回头改钱包（代价最高）
     */
    public record ReconciliationSnapshot(long orderCount, long totalAmount, long coinSegment,
            long cashSegment, long shippingCharged, long shippingPaidByCoin, long refundedTotal,
            long refundedCoin, long bonusIssued, long coinSpent, long bonusRedeemedApprox) {

        /** 两段之和必须等于订单实付 —— 这条对不上就是对账本身错了。 */
        public boolean segmentsBalance() {
            return coinSegment + cashSegment == totalAmount;
        }

        /** 现金段的实际净流入（已扣掉退款里的现金部分）。 */
        public long netCash() {
            return cashSegment - (refundedTotal - refundedCoin);
        }

        /** 分品类的售后成本行（供页面渲染）。 */
        public List<Map<String, Object>> emptyIfNull(List<Map<String, Object>> rows) {
            return rows == null ? new ArrayList<>() : rows;
        }
    }
}
