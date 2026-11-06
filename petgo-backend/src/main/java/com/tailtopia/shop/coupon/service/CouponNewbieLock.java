package com.tailtopia.shop.coupon.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新人券建 / 改串行锁（v1.4.2 shop Story 2.1 AC3.1，D-1）：事务级
 * {@code pg_advisory_xact_lock(hashtext('shop_coupon_newbie'))}，随事务自动释放。
 *
 * <p>时间区间重叠无法用排他约束表达（D-1），故「先锁、再查重叠、再插入」整段串行。
 * 用阻塞版（不是 try 版）：建券频率极低，等一下无妨，跳过反而让运营困惑。
 */
@Component
public class CouponNewbieLock {

    static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext('shop_coupon_newbie'))";

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        em.createNativeQuery(LOCK_SQL).getSingleResult();
    }
}
