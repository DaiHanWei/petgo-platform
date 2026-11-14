package com.tailtopia.healthreminder.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同一宠物的提醒写入串行化（V1.5.0 Story 2.1 · AC4.5）：两参 {@code pg_advisory_xact_lock(ns, petKey)}，随事务释放。
 * 命名空间 {@code 0x4852_5054} "HRPT"（不与 {@code PetSlotLock} 列出的已占用命名空间相撞）。重建、日跑、排队都取这一把。
 */
@Component
public class HealthReminderLock {

    static final int NS = 0x4852_5054;

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPet(long petId) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, :id)")
                .setParameter("ns", NS)
                .setParameter("id", (int) (petId ^ (petId >>> 32)))
                .getSingleResult();
    }
}
