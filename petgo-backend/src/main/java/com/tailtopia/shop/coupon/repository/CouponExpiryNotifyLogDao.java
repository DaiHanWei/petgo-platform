package com.tailtopia.shop.coupon.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * 快过期提醒去重日志 {@code coupon_expiry_notify_log}（v1.4.2 shop Story 2.1 建表；写入归 2.3）。
 * 本 story 只提供注销联动的清理（AC7）。
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
}
