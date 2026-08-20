package com.tailtopia.social.repository;

import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserHideRelation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 隐藏关系读写（Story 1.1）。
 *
 * <p>⚠️ <b>本仓储只允许 {@code com.tailtopia.social} 模块内部引用</b>。
 * {@code content} / {@code notify} / {@code auth} 三侧一律经 {@code social.read} 的只读端口，
 * 禁止直读本接口（AD-8 模块边界）。
 */
public interface UserHideRelationRepository extends JpaRepository<UserHideRelation, Long> {

    /** 是否存在任意来源的隐藏（五处过滤用：Feed / 运营干预位 / 评论 R1·R2 / 搜索列表 / 通知抑制）。 */
    boolean existsByHolderIdAndTargetId(long holderId, long targetId);

    /** 是否存在指定来源的隐藏。主页访问校验只认 {@link HideSource#BLOCK}（AD-11）。 */
    boolean existsByHolderIdAndTargetIdAndSource(long holderId, long targetId, HideSource source);

    /** 取指定来源的那一行（解除拉黑 / 幂等判定用）。 */
    Optional<UserHideRelation> findByHolderIdAndTargetIdAndSource(long holderId, long targetId,
            HideSource source);

    /** 某人主动拉黑的全部对象，按拉黑时间倒序（黑名单页，Story 1.5 消费）。 */
    List<UserHideRelation> findByHolderIdAndSourceOrderByCreatedAtDesc(long holderId, HideSource source);

    /**
     * 这批目标里，哪些还另有一条指定来源的隐藏行（黑名单页标「已举报」用）。
     *
     * <p>只取 id 不取实体：一次查询解决整页的标记，避免逐行 exists 打出 N+1。
     */
    @Query("SELECT r.targetId FROM UserHideRelation r "
            + "WHERE r.holderId = :holderId AND r.source = :source AND r.targetId IN :targetIds")
    List<Long> findTargetIdsByHolderAndSourceIn(@Param("holderId") long holderId,
            @Param("source") HideSource source, @Param("targetIds") List<Long> targetIds);

    /** 删除指定来源的那一行；返回受影响行数（解除拉黑只删 BLOCK 行）。 */
    long deleteByHolderIdAndTargetIdAndSource(long holderId, long targetId, HideSource source);

    /**
     * 同源幂等插入：已存在则什么都不做（不刷新时间戳），返回实际插入行数。
     *
     * <p>⚠️ 必须用 {@code ON CONFLICT DO NOTHING} 而不是「save + catch 唯一约束异常」——
     * 约束异常穿出 repo 代理时共享事务已被标记 rollback-only，catch 了也救不回来，
     * 外层提交时 UnexpectedRollbackException → 500（并发双击拉黑就能触发）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
            INSERT INTO user_hide_relations (holder_id, target_id, source, created_at, updated_at)
            VALUES (:holderId, :targetId, :source, now(), now())
            ON CONFLICT (holder_id, target_id, source) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("holderId") long holderId, @Param("targetId") long targetId,
            @Param("source") String source);
}
