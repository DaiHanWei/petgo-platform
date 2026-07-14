package com.tailtopia.profile.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物身份证全平台自增流水号分配 / 回收（Story 6.1，FR-49A）。
 *
 * <p>号池设计 = 高水位序列 {@code pet_serial_seq}（新号，从 1）+ 回收 free-list 表 {@code pet_serial_pool}
 * （删除释放的号）。分配优先出池、否则 {@code nextval} → 编号紧凑、被删的号可复用。
 *
 * <p>并发正确性：{@code pg_advisory_xact_lock} 把分配临界区串行化（≤500 DAU、分配罕见——仅用户主动生成身份证时，
 * 最简且可证无撞号）；{@code pet_profiles.serial_id} UNIQUE 约束为终极兜底。<b>纯 DB 内解决，不引入
 * MQ / 缓存层 / 新中间件</b>（架构护栏 F5）。参照 {@link com.tailtopia.admin.audit.service.AuditChainLock}。
 *
 * <p>{@link #allocate} / {@link #release} 须在已开启事务内调用（默认 REQUIRED 传播，join 调用方事务：
 * 生成走 {@code IdCardService.generateSerial}、回收走 {@code ProfileDeletionService.deleteByUserId}），
 * advisory 锁随该事务提交 / 回滚自动释放，保证「分配 → assign → save」与「release → delete」各自原子。
 */
@Service
public class SerialAllocationService {

    /** advisory 锁键：流水号分配单写入路径的固定常量（全应用唯一占用即可）。 */
    static final long SERIAL_ALLOC_LOCK_KEY = 0x5345_5249_414C_0601L; // "SERIAL"+0601(story6.1) 衍生常量

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 分配一个流水号。优先复用池中最小回收号（原子取出）；池空则取序列下一个。返回分配到的号（≥1）。
     */
    @Transactional
    public long allocate() {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", SERIAL_ALLOC_LOCK_KEY)
                .getSingleResult();

        // 优先复用池中最小回收号（原子取出：DELETE ... RETURNING）。
        List<?> reused = entityManager.createNativeQuery(
                        "DELETE FROM pet_serial_pool "
                                + "WHERE serial_id = (SELECT min(serial_id) FROM pet_serial_pool) "
                                + "RETURNING serial_id")
                .getResultList();
        if (!reused.isEmpty()) {
            return ((Number) reused.get(0)).longValue();
        }

        // 池空 → 高水位序列下一个。
        Number next = (Number) entityManager.createNativeQuery("SELECT nextval('pet_serial_seq')")
                .getSingleResult();
        return next.longValue();
    }

    /**
     * 释放流水号回池（档案删除时调用，须在删除同一事务内保证原子）。幂等：重复释放 {@code ON CONFLICT DO NOTHING}。
     */
    @Transactional
    public void release(long serial) {
        entityManager.createNativeQuery(
                        "INSERT INTO pet_serial_pool (serial_id) VALUES (:serial) ON CONFLICT DO NOTHING")
                .setParameter("serial", serial)
                .executeUpdate();
    }
}
