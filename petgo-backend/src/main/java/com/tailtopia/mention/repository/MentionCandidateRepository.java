package com.tailtopia.mention.repository;

import com.tailtopia.mention.domain.MentionCandidate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * @ 候选集读写（V1.3.0 batch-b1 Story 3.1 · AD-10）。
 *
 * <p>⚠️ <b>本仓储只允许 {@code com.tailtopia.mention} 模块内部引用。</b>
 */
public interface MentionCandidateRepository extends JpaRepository<MentionCandidate, Long> {

    /**
     * 记一次互动：有行就刷新时间，没有就插一行（<b>幂等</b>）。
     *
     * <h2>🔴 必须是 {@code ON CONFLICT DO UPDATE}，不能是 find-then-save</h2>
     * 两个人同时互动（评论 + 点赞几乎同时落地）会并发走到这里。find-then-save 在那一刻
     * 撞唯一约束，而约束异常穿出 repo 代理时<b>共享事务已被标记 rollback-only</b> ——
     * catch 了也救不回来，外层提交时 {@code UnexpectedRollbackException} → 500
     * （同 {@code UserHideRelationRepository#insertIfAbsent} 踩过的坑）。
     *
     * <p>⚠️ 时间戳由调用方传入而不是用 {@code now()}：同一次互动往两个方向各写一行，
     * 两行的时间必须一致 —— 用 {@code now()} 会差出几微秒，排序上就成了两个"不同新旧"的人。
     *
     * <h2>🔴 {@code GREATEST(...)} 不是装饰</h2>
     * 写入走 {@code @Async} 线程池（默认 core=8），同一对 (owner, candidate) 的两个事件
     * <b>完全可能乱序提交</b>：先到的是 10:00:05 的评论，后到的是 10:00:01 的点赞。
     * 无条件覆盖会把排序键<b>倒退回更旧的时间</b> —— 表现是一个刚跟你聊过的人在
     * @ 列表里莫名靠后，越过 50 条边界时甚至被当成"最旧的"直接淘汰
     * （code-review 2026-09-15）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO mention_candidates
                (owner_id, candidate_id, last_interacted_at, created_at, updated_at)
            VALUES (:ownerId, :candidateId, :at, :at, :at)
            ON CONFLICT (owner_id, candidate_id)
            DO UPDATE SET last_interacted_at = GREATEST(mention_candidates.last_interacted_at,
                                                        EXCLUDED.last_interacted_at),
                          updated_at         = EXCLUDED.updated_at
            """, nativeQuery = true)
    int touch(@Param("ownerId") long ownerId, @Param("candidateId") long candidateId,
            @Param("at") java.time.Instant at);

    /**
     * **写入时顺手淘汰**：只留该 owner 最新 {@code keep} 条，其余删掉（AC2）。
     *
     * <h2>🔴 为什么是写入时裁剪，而不是定时任务</h2>
     * 表的上界因此是**硬的**：行数 ≤ 用户数 × keep，与互动量无关。
     * 定时任务则意味着「两次扫描之间可以涨到任意大」，还多一个会挂掉的东西。
     * <p>⚠️ 护栏：<b>禁引调度中间件</b>（CLAUDE.md）。这里连 {@code @Scheduled} 都不需要。
     *
     * <p>排序键与取候选那条<b>完全一致</b>（{@code last_interacted_at DESC, id DESC}）——
     * 不一致的话，被裁掉的可能正是要显示的那一批。
     *
     * @return 删掉的行数（正常情况恒为 0，只有越界那一次才 > 0）
     */
    @Modifying
    @Query(value = """
            DELETE FROM mention_candidates
            WHERE owner_id = :ownerId
              AND id NOT IN (
                  SELECT id FROM mention_candidates
                  WHERE owner_id = :ownerId
                  ORDER BY last_interacted_at DESC, id DESC
                  LIMIT :keep
              )
            """, nativeQuery = true)
    int trimToNewest(@Param("ownerId") long ownerId, @Param("keep") int keep);

    /**
     * 某人的候选集，最近互动的在前。
     *
     * <p>⚠️ 这里**不做拉黑过滤**：过滤要的是双向判定 + 批量查另一张表，
     * 属于 service 的事（走 {@code social.read} 的统一出口，AD-7）。
     * <p>⚠️ 也**不接受任何关键词**：没有全局用户搜索（AC5），昵称过滤在客户端于这批人之内做。
     */
    @Query("SELECT c FROM MentionCandidate c WHERE c.ownerId = :ownerId "
            + "ORDER BY c.lastInteractedAt DESC, c.id DESC")
    List<MentionCandidate> findRecent(@Param("ownerId") long ownerId, Pageable pageable);

    /**
     * 这批 id 里，哪些**确实在** owner 的候选集里（Story 3.2 · AD-10 Rule 3 的服务端闸门）。
     *
     * <h2>🔴 判据是「在不在这张表里」，不是「在下发的那 30 个里」</h2>
     * 下发上限（{@code MentionCandidateQueryService.MAX_CANDIDATES}=30）是**展示**口径；
     * 表里留着 {@value MentionCandidateMaintenanceService#KEEP_PER_OWNER} 条。
     * 拿 30 去卡会造成一类假拒：用户打开选择器时某人排第 29，选中的瞬间又有两个人
     * 冒到前面 —— 提交时他掉到第 31，于是"刚刚还能选、现在 @ 不了了"。
     * 「打过交道」这件事没有排名，判存在就够。
     *
     * <p>⚠️ **一次批量**（AD-6）：这是发布 / 评论的同步写路径，逐个 exists 是 N 次往返。
     */
    @Query("SELECT c.candidateId FROM MentionCandidate c "
            + "WHERE c.ownerId = :ownerId AND c.candidateId IN :candidateIds")
    List<Long> findExistingCandidateIds(@Param("ownerId") long ownerId,
            @Param("candidateIds") java.util.Collection<Long> candidateIds);

    /**
     * 注销级联（D1/D2）：把这个人从候选集里**两个方向**都抹掉。
     *
     * <p>🔴 <b>两个方向都要删</b>：只删 {@code owner_id} 的话，他还会继续出现在**别人**的
     * @ 候选里 —— 一个已注销的账号被 @ 出来，点进去是「用户不存在」。
     */
    @Modifying
    @Query("DELETE FROM MentionCandidate c WHERE c.ownerId = :userId OR c.candidateId = :userId")
    int deleteAllForUser(@Param("userId") long userId);
}
