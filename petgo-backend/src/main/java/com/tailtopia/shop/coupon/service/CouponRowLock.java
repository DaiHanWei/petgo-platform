package com.tailtopia.shop.coupon.service;

import com.tailtopia.shop.coupon.domain.ShopCoupon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 券模板行锁 + 刷新（v1.4.2 shop Story 2.2 复审修复）：领取 / 兑换 / 新人发放串行同一模板。
 *
 * <p>🔴 不直接用 {@code ShopCouponRepository.findLockedById}：同事务里模板若已被先读进持久化上下文
 * （按 token 查、查当前新人券），{@code SELECT … FOR UPDATE} 虽会加锁，Hibernate 却返回<b>缓存里的旧实体</b>，
 * 之后的停用 / 发放期判断用的是加锁前的值。这里用 {@code refresh(entity, PESSIMISTIC_WRITE)}：
 * 加锁的同一条语句把状态重新读回实体。
 */
@Component
public class CouponRowLock {

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ShopCoupon> lockFresh(long couponId) {
        ShopCoupon c = em.find(ShopCoupon.class, couponId);
        if (c == null) {
            return Optional.empty();
        }
        em.refresh(c, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(c);
    }
}
