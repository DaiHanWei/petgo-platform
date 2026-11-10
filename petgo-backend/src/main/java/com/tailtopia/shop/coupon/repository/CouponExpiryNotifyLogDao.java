package com.tailtopia.shop.coupon.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;

/**
 * 快过期提醒去重日志 {@code coupon_expiry_notify_log}（v1.4.2 shop Story 2.1 建表；写入归 2.3）。
 *
 * <p>主键 {@code (user_id, wib_date)}：同一用户同一 WIB 日只一条提醒（Story 2.3 AC5.5.1）。
 * 2.1 提供注销联动的清理（AC7）；2.3 增加插入 / 更新张数 / 过期清理。
 */
@Repository
public class CouponExpiryNotifyLogDao {

    @PersistenceContext
    private EntityManager em;

    public int deleteByUserId(long userId) {
        return em.createNativeQuery("DELETE FROM coupon_expiry_notify_log WHERE user_id = :userId")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /**
     * 今天的提醒占位（张数先记 1，置位后由 {@link #updateCount} 改成实际张数）。
     *
     * @return 1 = 占位成功；0 = 今天已提醒过（主键冲突，什么都不做）
     */
    public int insertIfAbsent(long userId, LocalDate wibDate) {
        return em.createNativeQuery("""
                        INSERT INTO coupon_expiry_notify_log (user_id, wib_date, coupon_count)
                        VALUES (:userId, :wibDate, 1) ON CONFLICT DO NOTHING""")
                .setParameter("userId", userId)
                .setParameter("wibDate", wibDate)
                .executeUpdate();
    }

    public int updateCount(long userId, LocalDate wibDate, int couponCount) {
        return em.createNativeQuery("""
                        UPDATE coupon_expiry_notify_log SET coupon_count = :n
                         WHERE user_id = :userId AND wib_date = :wibDate""")
                .setParameter("n", couponCount)
                .setParameter("userId", userId)
                .setParameter("wibDate", wibDate)
                .executeUpdate();
    }

    /** 收尾清理（AC5.10）：删除 {@code wib_date < before} 的日志行（单条 DELETE）。 */
    public int deleteBefore(LocalDate before) {
        return em.createNativeQuery("DELETE FROM coupon_expiry_notify_log WHERE wib_date < :before")
                .setParameter("before", before)
                .executeUpdate();
    }
}
