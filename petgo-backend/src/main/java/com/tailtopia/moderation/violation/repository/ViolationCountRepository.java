package com.tailtopia.moderation.violation.repository;

import com.tailtopia.moderation.violation.domain.ViolationCount;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 账号违规计数仓储（内容审核 story 9）。原子 UPSERT 累加 + 按账号读/删（注销级联）。 */
public interface ViolationCountRepository extends JpaRepository<ViolationCount, Long> {

    /**
     * 原子累加（§5.2）：PostgreSQL {@code INSERT ... ON CONFLICT DO UPDATE} —— 存在则 +1、刷 last/updated；
     * 不存在则 INSERT count=1、first/last=now()。配 UNIQUE(account_id, violation_type) 避免并发读改写竞态。
     * {@code type} 传枚举 name()（native 绑定用字符串，避免 ordinal 歧义）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO violation_counts
                (account_id, violation_type, violation_count, first_violation_at, last_violation_at, created_at, updated_at)
            VALUES (:accountId, :type, 1, now(), now(), now(), now())
            ON CONFLICT (account_id, violation_type)
            DO UPDATE SET violation_count = violation_counts.violation_count + 1,
                          last_violation_at = now(),
                          updated_at = now()
            """, nativeQuery = true)
    void upsertIncrement(@Param("accountId") long accountId, @Param("type") String type);

    /** 某账号各类型计数快照（供 story 8 后台展示，经 ViolationCountReader）。 */
    List<ViolationCount> findByAccountId(long accountId);

    /** 注销级联删除该账号全部计数行（§5.5 D1/D2，user 行删除前调用）。 */
    @Modifying
    @Query("DELETE FROM ViolationCount v WHERE v.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") long accountId);
}
