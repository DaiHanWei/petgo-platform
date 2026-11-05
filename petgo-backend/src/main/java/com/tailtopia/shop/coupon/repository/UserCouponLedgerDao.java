package com.tailtopia.shop.coupon.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
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

    private static final String INSERT_SQL = """
            INSERT INTO user_coupons (public_token, user_id, coupon_id, source, status,
                                      valid_from, valid_until, claimed_at, created_at, updated_at)
            VALUES (:token, :userId, :couponId, :source, 'AVAILABLE',
                    :validFrom, :validUntil, :now, :now, :now)
            RETURNING id""";

    private static final String LOCK_SQL = """
            UPDATE user_coupons SET status = 'LOCKED', locked_order_id = :orderId, updated_at = :now
             WHERE public_token = :token AND user_id = :userId AND status = 'AVAILABLE'
               AND valid_from <= :now AND valid_until > :now""";

    private static final String MARK_USED_SQL = """
            UPDATE user_coupons SET status = 'USED', used_order_id = :orderId, updated_at = :now
             WHERE locked_order_id = :orderId AND status = 'LOCKED'""";

    /** 退回（D-3）：模板已停用且选了作废 → VOIDED，否则 → AVAILABLE；两种都清订单字段。 */
    private static final String RELEASE_RETURN_SQL = """
            UPDATE user_coupons uc SET
                status = CASE WHEN c.status = 'STOPPED' AND c.void_on_stop THEN 'VOIDED' ELSE 'AVAILABLE' END,
                locked_order_id = NULL, used_order_id = NULL, updated_at = :now
              FROM shop_coupons c
             WHERE uc.coupon_id = c.id AND uc.locked_order_id = :orderId AND uc.status IN ('LOCKED', 'USED')""";

    private static final String VOID_UNUSED_BY_COUPON_SQL = """
            UPDATE user_coupons SET status = 'VOIDED', updated_at = :now
             WHERE coupon_id = :couponId AND status = 'AVAILABLE'""";

    private static final String VOID_AVAILABLE_BY_USER_SQL = """
            UPDATE user_coupons SET status = 'VOIDED', updated_at = :now
             WHERE user_id = :userId AND status = 'AVAILABLE'""";

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

    /** 原生更新后同事务要再读 {@code UserCoupon} 时调用，避免读到一级缓存里的旧状态。 */
    public void clearPersistenceContext() {
        em.flush();
        em.clear();
    }
}
