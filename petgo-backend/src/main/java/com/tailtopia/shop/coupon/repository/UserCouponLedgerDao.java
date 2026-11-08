package com.tailtopia.shop.coupon.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 用户券<b>唯一写入口</b>（v1.4.2 shop Story 2.1 AC2.4，P-2）。
 *
 * <p>🔴 全仓库唯一出现用户券表 INSERT / UPDATE / DELETE 语句的文件，且只被 {@code CouponLedgerService} 注入
 * （{@code CouponLedgerSingleEntryGuardTest} 守卫）。新增写语句一律加在这里，由账本暴露业务方法。
 *
 * <p>全部是<b>条件语句</b>：并发安全靠 {@code WHERE status = …}，不靠读后写；影响行数即结果。
 *
 * <p>用 {@link EntityManager} 原生语句而不是 {@code JdbcTemplate}：同一事务里调用方（3.2 提交订单）
 * 刚 persist 的订单尚未 flush，Hibernate 在执行原生语句前会 auto-flush，{@code locked_order_id}
 * 外键才查得到订单行。⚠️ 原生更新绕过一级缓存：同事务先读过某张 {@code UserCoupon}、改状态后又要读，
 * 先 {@code flush(); clear()}（见 {@link #clearPersistenceContext()}）。
 */
@Repository
public class UserCouponLedgerDao {

    /*
     * V1.4.2 shop-admin Story 2.1 · AD-6：每条状态变更语句都用数据修改 CTE 在<b>同一条语句</b>里补写
     * user_coupon_events（ISSUED / LOCKED / RELEASED / USED / VOIDED 各一行），流水与状态不可能一边成一边不成。
     * 更新类语句的影响行数 = 写入的流水行数 = 本次改了状态的用户券数（与原 executeUpdate 语义一致）。
     */
    private static final String INSERT_SQL = """
            WITH n AS (
                INSERT INTO user_coupons (public_token, user_id, coupon_id, source, status,
                                          valid_from, valid_until, claimed_at, created_at, updated_at)
                VALUES (:token, :userId, :couponId, :source, 'AVAILABLE',
                        :validFrom, :validUntil, :now, :now, :now)
                RETURNING id
            ), e AS (
                INSERT INTO user_coupon_events (user_coupon_id, event, order_id, created_at)
                SELECT id, 'ISSUED', NULL, :now FROM n
            )
            SELECT id FROM n""";

    private static final String LOCK_SQL = """
            WITH u AS (
                UPDATE user_coupons SET status = 'LOCKED', locked_order_id = :orderId, updated_at = :now
                 WHERE public_token = :token AND user_id = :userId AND status = 'AVAILABLE'
                   AND valid_from <= :now AND valid_until > :now
                RETURNING id
            )
            INSERT INTO user_coupon_events (user_coupon_id, event, order_id, created_at)
            SELECT id, 'LOCKED', :orderId, :now FROM u""";

    private static final String MARK_USED_SQL = """
            WITH u AS (
                UPDATE user_coupons SET status = 'USED', used_order_id = :orderId, updated_at = :now
                 WHERE locked_order_id = :orderId AND status = 'LOCKED'
                RETURNING id
            )
            INSERT INTO user_coupon_events (user_coupon_id, event, order_id, created_at)
            SELECT id, 'USED', :orderId, :now FROM u""";

    /**
     * 迟到款重新下单重新锁券（v1.4.2 shop Story 4.2 AC2.4，D-3「不新建行，直接把原行重新 LOCKED 到新订单」）：
     * 仅 AVAILABLE → LOCKED 到新单；🔴 <b>不校验有效期</b>（PRD FR-134.2「券已过期仍可用于这张新订单」）。
     * LOCKED / USED（被别单占用或用掉）、VOIDED（运营停用作废）均影响 0 行。
     */
    public static final String RELOCK_FOR_REORDER_SQL = """
            WITH u AS (
                UPDATE user_coupons SET status = 'LOCKED', locked_order_id = :orderId, used_order_id = NULL,
                                        updated_at = :now
                 WHERE id = :id AND user_id = :userId AND status = 'AVAILABLE'
                RETURNING id
            )
            INSERT INTO user_coupon_events (user_coupon_id, event, order_id, created_at)
            SELECT id, 'LOCKED', :orderId, :now FROM u""";

    /**
     * 退回（D-3）：模板已停用且选了作废 → VOIDED，否则 → AVAILABLE；两种都清订单字段。
     * 流水：落 AVAILABLE 记 RELEASED，落 VOIDED 记 VOIDED，均带原订单。
     */
    private static final String RELEASE_RETURN_SQL = """
            WITH u AS (
                UPDATE user_coupons uc SET
                    status = CASE WHEN c.status = 'STOPPED' AND c.void_on_stop THEN 'VOIDED' ELSE 'AVAILABLE' END,
                    locked_order_id = NULL, used_order_id = NULL, updated_at = :now
                  FROM shop_coupons c
                 WHERE uc.coupon_id = c.id AND uc.locked_order_id = :orderId AND uc.status IN ('LOCKED', 'USED')
                RETURNING uc.id, uc.status
            )
            INSERT INTO user_coupon_events (user_coupon_id, event, order_id, created_at)
            SELECT id, CASE WHEN status = 'VOIDED' THEN 'VOIDED' ELSE 'RELEASED' END, :orderId, :now FROM u""";

    private static final String VOID_UNUSED_BY_COUPON_SQL = """
            WITH u AS (
                UPDATE user_coupons SET status = 'VOIDED', updated_at = :now
                 WHERE coupon_id = :couponId AND status = 'AVAILABLE'
                RETURNING id
            )
            INSERT INTO user_coupon_events (user_coupon_id, event, order_id, created_at)
            SELECT id, 'VOIDED', NULL, :now FROM u""";

    private static final String VOID_AVAILABLE_BY_USER_SQL = """
            WITH u AS (
                UPDATE user_coupons SET status = 'VOIDED', updated_at = :now
                 WHERE user_id = :userId AND status = 'AVAILABLE'
                RETURNING id
            )
            INSERT INTO user_coupon_events (user_coupon_id, event, order_id, created_at)
            SELECT id, 'VOIDED', NULL, :now FROM u""";

    /**
     * 快过期提醒置位（Story 2.3 AC5.5.2，I8「每张券只提醒一次」）：该用户 AVAILABLE、未提醒过、
     * 到期落在 {@code (now, until]} 的券置 {@code expiry_notified_at = now}，返回本次置位的到期时刻。
     */
    private static final String MARK_EXPIRY_NOTIFIED_SQL = """
            UPDATE user_coupons SET expiry_notified_at = :now, updated_at = :now
             WHERE user_id = :userId AND status = 'AVAILABLE' AND expiry_notified_at IS NULL
               AND valid_until > :now AND valid_until <= :until
            RETURNING valid_until""";

    @PersistenceContext
    private EntityManager em;

    /** 发券：插入一行 AVAILABLE，返回新 id。 */
    public long insert(String publicToken, long userId, long couponId, String source,
            Instant validFrom, Instant validUntil, Instant now) {
        Object id = em.createNativeQuery(INSERT_SQL)
                .setParameter("token", publicToken)
                .setParameter("userId", userId)
                .setParameter("couponId", couponId)
                .setParameter("source", source)
                .setParameter("validFrom", validFrom)
                .setParameter("validUntil", validUntil)
                .setParameter("now", now)
                .getSingleResult();
        return ((Number) id).longValue();
    }

    /** AVAILABLE 且下单时刻在有效期内 → LOCKED 到该订单。返回影响行数（0 = 不可用 / 不是本人的 / 已被占用）。 */
    public int lock(String publicToken, long userId, long orderId, Instant now) {
        return em.createNativeQuery(LOCK_SQL)
                .setParameter("orderId", orderId)
                .setParameter("now", now)
                .setParameter("token", publicToken)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /** 重新下单重新锁券（见 {@link #RELOCK_FOR_REORDER_SQL}）。返回影响行数（0 = 非 AVAILABLE 或非本人）。 */
    public int relockForReorder(long userCouponId, long userId, long orderId, Instant now) {
        return em.createNativeQuery(RELOCK_FOR_REORDER_SQL)
                .setParameter("orderId", orderId)
                .setParameter("now", now)
                .setParameter("id", userCouponId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    public int markUsed(long orderId, Instant now) {
        return em.createNativeQuery(MARK_USED_SQL)
                .setParameter("orderId", orderId)
                .setParameter("now", now)
                .executeUpdate();
    }

    public int releaseReturn(long orderId, Instant now) {
        return em.createNativeQuery(RELEASE_RETURN_SQL)
                .setParameter("orderId", orderId)
                .setParameter("now", now)
                .executeUpdate();
    }

    public int voidUnusedByCoupon(long couponId, Instant now) {
        return em.createNativeQuery(VOID_UNUSED_BY_COUPON_SQL)
                .setParameter("couponId", couponId)
                .setParameter("now", now)
                .executeUpdate();
    }

    public int voidAvailableByUser(long userId, Instant now) {
        return em.createNativeQuery(VOID_AVAILABLE_BY_USER_SQL)
                .setParameter("userId", userId)
                .setParameter("now", now)
                .executeUpdate();
    }

    /** 快过期提醒置位（见 {@link #MARK_EXPIRY_NOTIFIED_SQL}）。返回本次置位的券的到期时刻（无则空列表）。 */
    public List<Instant> markExpiryNotified(long userId, Instant now, Instant until) {
        List<?> rows = em.createNativeQuery(MARK_EXPIRY_NOTIFIED_SQL)
                .setParameter("userId", userId)
                .setParameter("now", now)
                .setParameter("until", until)
                .getResultList();
        List<Instant> out = new ArrayList<>(rows.size());
        for (Object row : rows) {
            out.add(toInstant(row));
        }
        return out;
    }

    /** 原生查询的时间列可能给回 Instant / OffsetDateTime / Timestamp（驱动与 Hibernate 版本决定），都收。 */
    static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalStateException("无法识别的时间类型: " + (value == null ? "null" : value.getClass()));
    }

    /** 原生更新后同事务要再读 {@code UserCoupon} 时调用，避免读到一级缓存里的旧状态。 */
    public void clearPersistenceContext() {
        em.flush();
        em.clear();
    }
}
