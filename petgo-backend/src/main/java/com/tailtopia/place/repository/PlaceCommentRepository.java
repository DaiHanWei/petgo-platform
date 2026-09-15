package com.tailtopia.place.repository;

import com.tailtopia.place.domain.PlaceComment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 场所评论读取（V1.3.0 batch-b1 Story 1.7 · AC6 拉黑过滤 · 安全攸关）。
 *
 * <h2>🔴 两层过滤，缺一层就是泄漏</h2>
 * <ol>
 *   <li><b>审核可见性</b>：一行对 viewer 可见 ⟺ {@code moderationStatus = VISIBLE}
 *       <b>或</b> {@code authorId = :viewerId}（作者始终看得见自己那条挂起/被拒的评论 ——
 *       否则他一提交就发现"发不出去"，先发后审的意义没了）。游客（{@code viewerId} 为 null）
 *       退化为仅 VISIBLE。</li>
 *   <li><b>R1 拉黑过滤</b>（AC6）：查看者隐藏了评论作者 → 该条对他不展示，
 *       <b>不区分来源</b>（主动拉黑与举报隐藏都算）。游客无 R1。</li>
 * </ol>
 *
 * <h2>🔴 为什么**没有** R2（影子评论）</h2>
 * 内容评论的 R2 是「<b>内容作者</b>隐藏了评论作者 → 对所有人不展示」——它的前提是
 * 「这是我的地盘」。而**场所没有主人**：标记人既不能编辑也不能删除它（2026-09-15 拍板），
 * 场所是共享的地点条目。把 R2 照抄过来等于凭空给标记人一份对公共条目的屏蔽权 ——
 * 那不在任何 AC 里，而且会让「我标记的店，我看谁不顺眼就让他的评论对全世界消失」成立。
 * ⚠️ 所以这里的参数里**没有** postAuthorId 的对应物，这是有意的，不是漏了。
 *
 * <h2>⚠️ 游客分支用 {@code :hasViewer} 布尔门控</h2>
 * 不写裸 {@code :viewerId IS NULL} —— PG 推不出 NULL 参数类型会报
 * <b>42P18 could not determine data type</b>（同 {@code CommentRepository} 的既定处理）。
 *
 * <p>跨模块引用说明：JPQL 里写 {@code social} 的实体名 {@code UserHideRelation}，Java 侧不 import
 * 其仓储 —— 与 {@code CommentRepository} 同一既定破例。
 */
public interface PlaceCommentRepository extends JpaRepository<PlaceComment, Long> {

    /**
     * 某场所对 viewer 可见的评论，按 {@code created_at DESC, id DESC} 游标分页。
     *
     * <p>🔴 **最新在前**：场所评论是攻略提示（「周末户外座位很少」），最新的那条才有参考价值；
     * 内容评论按时间正序是因为它有对话语境（楼中楼），这里没有。
     */
    @Query("""
            SELECT c FROM PlaceComment c
            WHERE c.placeId = :placeId AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:hasViewer = true AND c.authorId = :viewerId))
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h
                                  WHERE h.holderId = :viewerId AND h.targetId = c.authorId))
              AND (:seek = false
                   OR c.createdAt < :cursorAt
                   OR (c.createdAt = :cursorAt AND c.id < :cursorId))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<PlaceComment> findVisiblePage(@Param("placeId") long placeId,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId,
            @Param("seek") boolean seek,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * 某场所对 viewer 可见的评论数（详情页「KOMENTAR (3)」那个数字）。
     *
     * <p>🔴 **过滤条件必须与 {@link #findVisiblePage} 逐字一致**：这个数字与下面列出来的条数
     * 是同一屏上的两样东西，对不上就是穿帮 ——「标题写着 3 条，往下数只有 2 条」正好把
     * 「我拉黑了谁」暴露给用户自己（这倒无害），更要紧的是它让人以为有条评论加载失败了。
     */
    @Query("""
            SELECT COUNT(c) FROM PlaceComment c
            WHERE c.placeId = :placeId AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:hasViewer = true AND c.authorId = :viewerId))
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h
                                  WHERE h.holderId = :viewerId AND h.targetId = c.authorId))
            """)
    long countVisibleForViewer(@Param("placeId") long placeId,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId);

    /** 一批场所各自对 viewer 可见的评论数（AD-6 批量取，列表页不要 N+1）。 */
    @Query("""
            SELECT c.placeId, COUNT(c) FROM PlaceComment c
            WHERE c.placeId IN :placeIds AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:hasViewer = true AND c.authorId = :viewerId))
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h
                                  WHERE h.holderId = :viewerId AND h.targetId = c.authorId))
            GROUP BY c.placeId
            """)
    List<Object[]> countVisibleByPlaceIds(@Param("placeIds") List<Long> placeIds,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId);

    /**
     * 一批场所各自的**可见**态度计数（Story 1.8 · AD-9 的 DB 回算口径）。
     *
     * <p>🔴 **不是 viewer 维度**：这两个数字是"大家觉得这地方行不行"的**平台口径**，
     * 与谁在看无关。对比 {@link #countVisibleByPlaceIds}（评论数）——那个必须按 viewer 过滤，
     * 因为它要与列出来的条数一致。两者口径不同是**有意的**，不要"统一"它们。
     *
     * <p>口径：未删 + {@code moderation_status = VISIBLE} + 有态度。
     * ⚠️ 必须与 {@code PlaceAttitudeCounters} 的增减时机逐字一致，否则自愈一跑数字就变。
     *
     * @return 每行 {@code [placeId, attitude, count]}
     */
    @Query("""
            SELECT c.placeId, c.attitude, COUNT(c) FROM PlaceComment c
            WHERE c.placeId IN :placeIds AND c.deletedAt IS NULL
              AND c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
              AND c.attitude IS NOT NULL
            GROUP BY c.placeId, c.attitude
            """)
    List<Object[]> countVisibleAttitudesByPlaceIds(@Param("placeIds") List<Long> placeIds);

    /** 删除 / 审核处置用：按 id 取未删的那条。 */
    Optional<PlaceComment> findByIdAndDeletedAtIsNull(long id);

    /**
     * 注销级联用：某人全部未删的场所评论（NFR-8 / D1/D2）。
     *
     * <p>⚠️ 不分页、全量取：注销是一次性作业，单人评论量级极小；
     * 分页反而会在"边改边翻页"时漏掉行。
     */
    List<PlaceComment> findByAuthorIdAndDeletedAtIsNull(long authorId);
}
