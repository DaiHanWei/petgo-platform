package com.tailtopia.profile.slot;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 名额相关写操作的<b>唯一账号锁</b>（V1.4.0 multi-pet Story 1.4 · AD-4）：同一账号并发建档按账号串行化，
 * 「计数 → 比名额 → 落库」不会被两个请求同时通过。Story 2.1 的 {@code PetSlotStateService.reconcile}、
 * 2.2 的月租发放都复用 {@link #lockUser}。
 *
 * <p>两参 {@code pg_advisory_xact_lock(ns, uid)}（照 {@code CardNumberService.lockOwnerForCardCreation}）：
 * 单参形式与 {@code hashtext(...)} / {@code AuditChainLock} / {@code SerialAllocationService} 等单参键共用一个
 * bigint 空间，有撞键风险。已占用的两参命名空间（不得相撞）：
 * <ul>
 *   <li>{@code 0x4943_4352} "ICCR" —— {@code CardNumberService} 建卡</li>
 *   <li>{@code 0x5453_5352} "TSSR" —— {@code TailsonalityShareRewardService}</li>
 *   <li>{@code 0x5050_5352} "PPSR" —— {@code PassportShareRewardService}</li>
 *   <li>{@code 0x4147_4543} "AGEC" —— {@code AgeCardShareRewardService}</li>
 * </ul>
 */
@Component
public class PetSlotLock {

    /** "PETS"。 */
    static final int PET_SLOT_LOCK_NS = 0x5045_5453;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY) // 事务外调用即失败：xact 锁离开事务就等于没锁
    public void lockUser(long userId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, :uid)")
                .setParameter("ns", PET_SLOT_LOCK_NS)
                .setParameter("uid", (int) (userId % Integer.MAX_VALUE))
                .getSingleResult();
    }
}
