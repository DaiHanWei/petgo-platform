package com.tailtopia.admin.shop.service;

import com.tailtopia.shop.repurchase.domain.RepurchaseTriggerType;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复购引擎效果看板（Story 6.6，AB-13B）。
 *
 * <p>🔴🔴 <b>以服务端业务库为主口径</b>（S-13）：直接读 Story 3.4 已在 {@code shop_order_lines}
 * 上持久化的 {@code entry_source} / {@code trigger_type}。
 * <b>PostHog 仅作辅助交叉验证，后台不反拉它的 API</b>（会引入外部依赖，违 NFR-1）。
 * 理由：AB-13B 是裁决 <b>A-16</b> 的唯一依据，不能建立在带丢失率与广告拦截偏差的三方 SaaS 上（L-6）。
 *
 * <p>⚠️ <b>看板评的是两条机制不是三条</b>：FR-108（驱虫/疫苗周期提醒）已挪 1.2.0（C-11）。
 * {@code DEWORM} / {@code VACCINE} 在本版本<b>恒为 0 —— 这是范围决策，不是数据丢失</b>。
 * 页面必须写明这一句，否则运营会报「埋点坏了」。
 *
 * <p>🔴 <b>「FR-109 触发覆盖率」是 DEP-6 是否到位的直接读数</b>：
 * 长期接近 0 说明<b>喂量数据没填</b>，而不是机制无效。看板必须让人一眼看出这个区别 ——
 * 否则复购引擎会被错误地判死。
 */
@Service
public class RepurchaseDashboardService {

    private final JdbcTemplate jdbc;

    public RepurchaseDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取一份完整看板数据。 */
    @Transactional(readOnly = true)
    public Snapshot snapshot(LocalDate from, LocalDate to) {
        java.sql.Timestamp fromTs = java.sql.Timestamp.valueOf(from.atStartOfDay());
        java.sql.Timestamp toTs = java.sql.Timestamp.valueOf(to.plusDays(1).atStartOfDay());

        Map<String, Long> triggersByType = new LinkedHashMap<>();
        // 🔴 三个类型都列出来（含恒为 0 的两个）—— 让「为什么是 0」有地方解释
        for (RepurchaseTriggerType t : RepurchaseTriggerType.values()) {
            triggersByType.put(t.name(), countTriggers(t.name(), fromTs, toTs));
        }

        long triggerTotal = triggersByType.values().stream().mapToLong(Long::longValue).sum();
        long triggerConverted = countScalar("""
                SELECT count(*) FROM repurchase_triggers
                WHERE status = 'CONVERTED' AND created_at >= ? AND created_at < ?
                """, fromTs, toTs);
        long triggerDismissed = countScalar("""
                SELECT count(*) FROM repurchase_triggers
                WHERE status = 'DISMISSED' AND created_at >= ? AND created_at < ?
                """, fromTs, toTs);

        // 🔴 转化以【订单行的归因】为准（服务端权威口径，S-13），不是以卡片状态为准 ——
        //    卡片状态是我们自己写的，订单行归因是用户真的买了。
        long linesFromTrigger = countScalar("""
                SELECT count(*) FROM shop_order_lines l JOIN shop_orders o ON o.id = l.order_id
                WHERE l.trigger_type IS NOT NULL AND o.created_at >= ? AND o.created_at < ?
                """, fromTs, toTs);
        long linesFromProfileReco = countScalar("""
                SELECT count(*) FROM shop_order_lines l JOIN shop_orders o ON o.id = l.order_id
                WHERE l.entry_source = 'PROFILE_RECO' AND o.created_at >= ? AND o.created_at < ?
                """, fromTs, toTs);
        long linesTotal = countScalar("""
                SELECT count(*) FROM shop_order_lines l JOIN shop_orders o ON o.id = l.order_id
                WHERE o.created_at >= ? AND o.created_at < ?
                """, fromTs, toTs);

        // 🔴 FR-109 触发覆盖率 = 有触发的用户 ÷ 有 Makanan 购买历史的用户
        long usersWithFoodPurchase = countScalar("""
                SELECT count(DISTINCT o.user_id)
                FROM shop_orders o
                JOIN shop_order_lines l ON l.order_id = o.id
                JOIN shop_skus s ON s.id = l.sku_id
                JOIN shop_products p ON p.id = s.product_id
                WHERE p.category = 'MAKANAN' AND o.created_at < ?
                """, toTs);
        long usersWithTrigger = countScalar("""
                SELECT count(DISTINCT user_id) FROM repurchase_triggers WHERE created_at < ?
                """, toTs);

        // 复购率（30/60/90 日）：首单之后 N 日内又下过单的用户占比
        Map<Integer, Double> repurchaseRates = new LinkedHashMap<>();
        for (int window : List.of(30, 60, 90)) {
            repurchaseRates.put(window, repurchaseRate(window));
        }

        // 粮量预估准确度：预估耗尽日 vs 实际再购日的偏差（天），只统计已被再次购买超越的触发
        Deviation deviation = depletionDeviation();

        return new Snapshot(triggersByType, triggerTotal, triggerConverted, triggerDismissed,
                linesFromTrigger, linesFromProfileReco, linesTotal,
                usersWithFoodPurchase, usersWithTrigger, repurchaseRates, deviation);
    }

    private long countTriggers(String type, java.sql.Timestamp from, java.sql.Timestamp to) {
        return countScalar("""
                SELECT count(*) FROM repurchase_triggers
                WHERE trigger_type = ? AND created_at >= ? AND created_at < ?
                """, type, from, to);
    }

    private long countScalar(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    /** 首单后 N 日内再次下单的用户占比。分母是有过至少一单的用户。 */
    private double repurchaseRate(int days) {
        Long denominator = jdbc.queryForObject(
                "SELECT count(DISTINCT user_id) FROM shop_orders", Long.class);
        if (denominator == null || denominator == 0) {
            return 0;
        }
        Long numerator = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT o.user_id, MIN(o.created_at) AS first_at
                    FROM shop_orders o GROUP BY o.user_id
                ) f
                WHERE EXISTS (
                    SELECT 1 FROM shop_orders o2
                    WHERE o2.user_id = f.user_id AND o2.created_at > f.first_at
                      AND o2.created_at <= f.first_at + make_interval(days => ?)
                )
                """, Long.class, days);
        return numerator == null ? 0 : (double) numerator / denominator;
    }

    /**
     * 粮量预估准确度：预估耗尽日 vs 实际再购日的偏差分布。
     *
     * <p>只统计<b>已被再次购买超越</b>的触发（{@code SUPERSEDED}）—— 还没再买的没有「实际再购日」，
     * 把它们算进来会让分布整体偏向「预估太早」。
     */
    private Deviation depletionDeviation() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.estimated_depletion_date AS est,
                       (SELECT MIN(o.created_at) FROM shop_orders o
                        JOIN shop_order_lines l ON l.order_id = o.id
                        WHERE o.user_id = t.user_id AND l.sku_id = t.sku_id
                          AND o.created_at > t.created_at) AS actual
                FROM repurchase_triggers t
                WHERE t.status = 'SUPERSEDED'
                """);
        long n = 0;
        long sum = 0;
        long minDev = Long.MAX_VALUE;
        long maxDev = Long.MIN_VALUE;
        for (Map<String, Object> r : rows) {
            Object est = r.get("est");
            Object actual = r.get("actual");
            if (est == null || actual == null) {
                continue;
            }
            LocalDate estDate = ((java.sql.Date) est).toLocalDate();
            LocalDate actualDate = ((java.sql.Timestamp) actual).toLocalDateTime().toLocalDate();
            long dev = java.time.temporal.ChronoUnit.DAYS.between(estDate, actualDate);
            n++;
            sum += dev;
            minDev = Math.min(minDev, dev);
            maxDev = Math.max(maxDev, dev);
        }
        if (n == 0) {
            return new Deviation(0, 0, 0, 0);
        }
        return new Deviation(n, (double) sum / n, minDev, maxDev);
    }

    /**
     * 看板快照。
     *
     * @param usersWithFoodPurchase 🔴 FR-109 触发覆盖率的分母
     * @param usersWithTrigger      🔴 分子。<b>长期接近 0 = 喂量数据没填（DEP-6），不是机制无效</b>
     */
    public record Snapshot(
            Map<String, Long> triggersByType,
            long triggerTotal,
            long triggerConverted,
            long triggerDismissed,
            long linesFromTrigger,
            long linesFromProfileReco,
            long linesTotal,
            long usersWithFoodPurchase,
            long usersWithTrigger,
            Map<Integer, Double> repurchaseRates,
            Deviation depletionDeviation) {

        /** 触发卡转化率 = 触发记录里最终转化的比例。 */
        public double triggerConversionRate() {
            return triggerTotal == 0 ? 0 : (double) triggerConverted / triggerTotal;
        }

        /** 🔴 FR-109 触发覆盖率 —— DEP-6 是否到位的直接读数。 */
        public double triggerCoverage() {
            return usersWithFoodPurchase == 0 ? 0
                    : (double) usersWithTrigger / usersWithFoodPurchase;
        }

        /** 🔴 判定 A-16 的直接依据：触发卡带来的订单行占比 vs 全站。 */
        public double triggerLineShare() {
            return linesTotal == 0 ? 0 : (double) linesFromTrigger / linesTotal;
        }

        public double profileRecoLineShare() {
            return linesTotal == 0 ? 0 : (double) linesFromProfileReco / linesTotal;
        }

        /**
         * ⚠️ <b>覆盖率为 0 且有粮购买历史</b> → 页面要显式提示「这是 DEP-6 数据没到位，
         * 不是机制无效」。不提示的话，运营会得出完全相反的结论。
         */
        public boolean looksLikeMissingFeedingData() {
            return usersWithFoodPurchase > 0 && usersWithTrigger == 0;
        }
    }

    /** 偏差分布（天）。正值 = 用户比预估晚买，负值 = 早买。 */
    public record Deviation(long sampleCount, double avgDays, long minDays, long maxDays) {
    }
}
