package com.tailtopia.admin.audit.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * 审计链写入串行化锁（Story 1.3，AG-2）。用 Postgres <b>事务级 advisory 锁</b>
 * （{@code pg_advisory_xact_lock}）把整个「取链尾 → 算哈希 → append」临界区串行化：
 * 同一时刻仅一个事务持锁，提交/回滚时自动释放，**不引入任何队列/中间件**（F5）。
 *
 * <p>为何不用 {@code SELECT ... ORDER BY id DESC LIMIT 1 FOR UPDATE}：READ COMMITTED 下
 * {@code LIMIT + FOR UPDATE} 可能在并发插入时返回过期链尾 → 两行取到同一 {@code prevHash} → 链分叉。
 * advisory 锁覆盖整段临界区，从根上杜绝竞态空洞。后台写并发极低，正确性优先。
 *
 * <p>抽成独立 bean 是为让 {@code AdminAuditService} 的哈希链单测（mock 本锁）无需 DB/EntityManager。
 */
@Component
public class AuditChainLock {

    /** advisory 锁键：审计链单写入路径的固定常量（任取一个稳定值，全应用唯一占用即可）。 */
    static final long AUDIT_CHAIN_LOCK_KEY = 0x4144_4954_4C4F_4701L; // "ADITLOG" 衍生常量

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 获取事务级 advisory 锁；必须在已开启的事务内调用（{@code AdminAuditService.record} 的 {@code @Transactional}
     * 范围内），锁随该事务提交/回滚自动释放。若锁已被其他事务持有则阻塞至释放。
     */
    public void acquire() {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", AUDIT_CHAIN_LOCK_KEY)
                .getSingleResult();
    }
}
