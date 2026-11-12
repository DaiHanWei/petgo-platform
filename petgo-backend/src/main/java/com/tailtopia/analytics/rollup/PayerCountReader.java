package com.tailtopia.analytics.rollup;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 业务库窄查询：比率指标的分子（analytics-pipeline Story 4.7，S4 / 产品 #3 / 内容 #1，D-13 / 架构「边界」）。
 *
 * <p>每个方法给定 UTC 区间 {@code [from, to)}（调用方传 WIB 日 0 点），返回该类付费人的 {@code user_id} 集合——
 * <b>只在 JVM 内存里用来去重计数</b>，不落库、不写埋点库、不打日志。在埋点库单元事务之外调用（{@link RollupFamily#prepareDay}）。
 * 用 {@code @Primary} 业务库 {@code JdbcTemplate}，不注入业务模块的 Repository / Service。
 *
 * <h2>判据（D-13 逐表列出，已核对代码）</h2>
 * 🔴 PawCoin 付款不经过 {@code payment_intents}（扣币直接写各业务购买行，只有 QRIS 才建支付意图），所以不能只查
 * {@code payment_intents.status = 'PAID'}。所有查询 join {@code users} 只算 {@code role = 'USER' AND account_type = 'REAL'}；
 * {@code MERCHANT_PO}（门店进货单）的 {@code payment_intents.user_id} 是负的门店 id，与用户不相交，join {@code users} 时自然排除；
 * {@code SHOP_ORDER}（Toko）只进 S4，不进产品 #3。只 {@code SELECT user_id}，不读金额与任何个人字段。
 */
@Component
public class PayerCountReader {

    private static final String USER_FILTER = " AND u.role = 'USER' AND u.account_type = 'REAL'";

    /** S4：已付款 Toko 订单的下单人（D-13：paid_at 非空，退款 / 取消仍算）。 */
    static final String TOKO_BUYERS_SQL = "SELECT DISTINCT o.user_id FROM shop_orders o JOIN users u ON u.id = o.user_id "
            + "WHERE o.paid_at >= ? AND o.paid_at < ?" + USER_FILTER;

    /**
     * KTP 高清解锁。QRIS：{@code payment_intents} {@code purpose = 'ID_HD' AND status = 'PAID'}，按 {@code updated_at}
     * （没有 paid_at 列，updated_at 近似到账时刻）——QRIS 在发起时就写 {@code id_card_hd_purchases} 的 attempt 行，故不能按购买行数；
     * PawCoin：{@code id_card_hd_purchases} {@code pay_channel = 'PAWCOIN'}，按 {@code purchased_at}。两渠道 UNION 去重。
     */
    static final String KTP_SQL = "SELECT p.user_id FROM payment_intents p JOIN users u ON u.id = p.user_id "
            + "WHERE p.purpose = 'ID_HD' AND p.status = 'PAID' AND p.updated_at >= ? AND p.updated_at < ?" + USER_FILTER
            + " UNION "
            + "SELECT h.user_id FROM id_card_hd_purchases h JOIN users u ON u.id = h.user_id "
            + "WHERE h.pay_channel = 'PAWCOIN' AND h.purchased_at >= ? AND h.purchased_at < ?" + USER_FILTER;

    /** Tailsonality 结果 + 配型解锁：只认 PAID（DUPLICATE_PAID / ORPHAN_PAID 交人工，不算），按 paid_at。 */
    static final String TAILSONALITY_SQL = keepsake("'TAILSONALITY', 'TS_MATCH'");

    /** 护照快照 + 登机牌：只认 PAID，按 paid_at。 */
    static final String PASSPORT_SQL = keepsake("'PASSPORT_SNAP', 'BOARDING_PASS'");

    /** 宠物名额月租：PAID 且 kind IN (RENT, RENEW)（排除运营补发 ADMIN_GRANT），按 paid_at。 */
    static final String PET_SLOT_SQL = "SELECT DISTINCT s.user_id FROM pet_slot_purchases s JOIN users u ON u.id = s.user_id "
            + "WHERE s.status = 'PAID' AND s.kind IN ('RENT', 'RENEW') AND s.paid_at >= ? AND s.paid_at < ?"
            + USER_FILTER;

    /** AI 问诊解锁：COMPLETED（PawCoin 直接建成此态；QRIS 到账后置），按 paid_at。 */
    static final String AI_UNLOCK_SQL = "SELECT DISTINCT a.user_id FROM ai_consult_orders a JOIN users u ON u.id = a.user_id "
            + "WHERE a.status = 'COMPLETED' AND a.paid_at >= ? AND a.paid_at < ?" + USER_FILTER;

    /** 兽医问诊：paid_at 非空（含之后退款的，D-13），按 paid_at。 */
    static final String VET_CONSULT_SQL = "SELECT DISTINCT c.user_id FROM consult_orders c JOIN users u ON u.id = c.user_id "
            + "WHERE c.paid_at >= ? AND c.paid_at < ?" + USER_FILTER;

    /** 参照（不进产品 #3）：送礼付款人 sender_id；已付款的终态，按 delivered_at、无则 created_at。 */
    static final String GIFT_SQL = "SELECT DISTINCT g.sender_id FROM gift_orders g JOIN users u ON u.id = g.sender_id "
            + "WHERE g.status IN ('DELIVERED', 'UNDELIVERED_REFUND_PENDING', 'UNDELIVERED_REFUNDED', 'REFUND_MANUAL') "
            + "AND COALESCE(g.delivered_at, g.created_at) >= ? AND COALESCE(g.delivered_at, g.created_at) < ?"
            + USER_FILTER;

    /** 参照（不进产品 #3）：PawCoin 充值，按 updated_at（近似到账时刻）。 */
    static final String TOPUP_SQL = "SELECT DISTINCT p.user_id FROM payment_intents p JOIN users u ON u.id = p.user_id "
            + "WHERE p.purpose = 'PAWCOIN_TOPUP' AND p.status = 'PAID' AND p.updated_at >= ? AND p.updated_at < ?"
            + USER_FILTER;

    /** 内容 #1：当天注册的 USER + REAL 人数（业务库 users.created_at）。 */
    static final String NEW_USERS_SQL = "SELECT count(*) FROM users u WHERE u.created_at >= ? AND u.created_at < ?"
            + USER_FILTER;

    private static String keepsake(String skus) {
        return "SELECT DISTINCT k.user_id FROM keepsake_purchases k JOIN users u ON u.id = k.user_id "
                + "WHERE k.sku IN (" + skus + ") AND k.status = 'PAID' AND k.paid_at >= ? AND k.paid_at < ?"
                + USER_FILTER;
    }

    private final JdbcTemplate jdbc;

    public PayerCountReader(JdbcTemplate businessJdbcTemplate) {
        this.jdbc = businessJdbcTemplate;
    }

    /** S4 分子：已付款 Toko 订单的下单人。 */
    public Set<Long> tokoBuyers(Instant from, Instant to) {
        return ids(TOKO_BUYERS_SQL, from, to);
    }

    /** KTP 高清解锁（QRIS + PawCoin）。 */
    public Set<Long> ktpPayers(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            return Set.of();
        }
        OffsetDateTime f = utc(from);
        OffsetDateTime t = utc(to);
        Set<Long> out = new HashSet<>();
        jdbc.query(KTP_SQL, rs -> {
            out.add(rs.getLong(1));
        }, f, t, f, t);
        return out;
    }

    /** Tailsonality 结果 + 配型解锁。 */
    public Set<Long> tailsonalityPayers(Instant from, Instant to) {
        return ids(TAILSONALITY_SQL, from, to);
    }

    /** 护照快照 + 登机牌。 */
    public Set<Long> passportPayers(Instant from, Instant to) {
        return ids(PASSPORT_SQL, from, to);
    }

    /** 宠物名额月租（RENT / RENEW）。 */
    public Set<Long> petSlotPayers(Instant from, Instant to) {
        return ids(PET_SLOT_SQL, from, to);
    }

    /** AI 问诊解锁。 */
    public Set<Long> aiUnlockPayers(Instant from, Instant to) {
        return ids(AI_UNLOCK_SQL, from, to);
    }

    /** 兽医问诊。 */
    public Set<Long> vetConsultPayers(Instant from, Instant to) {
        return ids(VET_CONSULT_SQL, from, to);
    }

    /** 参照：送礼付款人。 */
    public Set<Long> giftSenders(Instant from, Instant to) {
        return ids(GIFT_SQL, from, to);
    }

    /** 参照：PawCoin 充值人。 */
    public Set<Long> topupPayers(Instant from, Instant to) {
        return ids(TOPUP_SQL, from, to);
    }

    /** 内容 #1：当天注册的 USER + REAL 人数。 */
    public long newUsers(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            return 0;
        }
        Long n = jdbc.queryForObject(NEW_USERS_SQL, Long.class, utc(from), utc(to));
        return n == null ? 0 : n;
    }

    private Set<Long> ids(String sql, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            return Set.of();
        }
        Set<Long> out = new HashSet<>();
        jdbc.query(sql, rs -> {
            out.add(rs.getLong(1));
        }, utc(from), utc(to));
        return out;
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
