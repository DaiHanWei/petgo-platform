package com.tailtopia.content.repository;

import com.tailtopia.content.domain.Comment;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 评论读取（Story 3.3）+ viewer 维度可见性过滤（内容审核 story 3，§5.5，D-CM2，安全攸关 R6）。
 *
 * <p>一行评论对 viewer 可见 ⟺ {@code moderationStatus = VISIBLE} <b>或</b> {@code authorId = :viewerId}
 * （作者始终看得到自己的挂起/下架评论）。{@code :viewerId} 为 null（游客）时退化为仅 VISIBLE。
 * <b>勿在任何查询遗漏该过滤 —— 泄漏 = 安全事故。</b>
 *
 * <h2>V1.1.4 Story 1.3：隐藏关系两条过滤 R1 / R2（FR-94，安全攸关）</h2>
 *
 * <p><b>R1（按查看者）</b>：查看者隐藏了评论作者 → 该条对他不展示。<b>不区分来源</b>（主动拉黑与举报隐藏都算）。
 * <br><b>R2（影子评论，按内容作者）</b>：<b>内容作者</b>隐藏了评论作者 → 该条对<b>所有人</b>不展示，
 * <b>只有评论作者自己看得见</b>（无感知）。
 *
 * <p><b>⚠️ 三处一字之差就全错，改这四条 JPQL 前必读：</b>
 * <ol>
 *   <li><b>新条件与既有的「VISIBLE 或 我自己」括号并列 {@code AND}，绝不能塞进那个 {@code OR} 括号里。</b>
 *       塞进去的话「作者本人」那条 OR 支路会<b>绕过拉黑过滤</b> —— 编译通过、绝大多数场景也正常，
 *       只在「被隐藏者恰好是评论作者本人」时暴露。</li>
 *   <li><b>R2 判的是 {@code holder = :postAuthorId}（内容作者），不是当前查看者。</b>
 *       做成按查看者判 → 第三方照样看得见，<b>等于没做</b>。</li>
 *   <li><b>R1 与 R2 的「作者本人自视豁免」不对称</b>：R2 必须放过 {@code c.authorId = :viewerId}
 *       （被影子的人要看得见自己那条，否则他立刻知道被屏蔽了）；R1 不需要豁免（我不会拉黑我自己）。
 *       写成对称的两条 {@code NOT EXISTS} 就直接破坏了「无感知」。</li>
 * </ol>
 *
 * <p><b>游客分支一律用 {@code :hasViewer} 布尔标志门控</b>，不写裸 {@code :viewerId IS NULL} ——
 * PG 推不出 NULL 参数类型会报 <b>42P18 could not determine data type</b>（与 {@code ContentPostRepository.findFeed} 同款处理）。
 *
 * <p><b>跨模块引用说明</b>：子查询里写的是 {@code social} 的实体名 {@code UserHideRelation}，Java 侧不 import 其仓储 ——
 * 与 {@code findFeed} 在 JPQL 里引用 {@code moderation} 的 {@code ContentReport} 是同一既定破例（AD-5 优先于 AD-8 的字面）。
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 某帖未删评论总数（含一级+二级）。<b>已弃用于 detail commentCount</b>，改用 viewer 维度计数。 */
    long countByPostIdAndDeletedAtIsNull(long postId);

    /**
     * 概览看板（bug 20260731-442）：仅真实用户发的评论（剔除虚拟/种子账号）。
     *
     * <p>⚠️ <b>不套 R1/R2</b>（Story 1.3 AC5）——这是<b>平台口径</b>的总量统计，与任何 viewer 无关。
     * 给它加隐藏过滤会让运营看到的总量随「谁在看」而变，那是错的。
     */
    @Query("select count(c) from Comment c join User u on u.id = c.authorId "
            + "where u.accountType = com.tailtopia.auth.domain.AccountType.REAL")
    long countByRealAuthor();

    /**
     * 某帖 viewer 可见的未删评论总数（detail 的 commentCount，含一级+二级）。
     * 公开可见（VISIBLE）+ viewer 自己的非可见评论，使渲染列表与计数一致（§5.5，R3 接受轻微 viewer 差异）。
     *
     * <p><b>口径（Story 1.3 AC5 / AD-13，下游功能勿再自行解释）—— 分两层，别混为一谈：</b>
     * <ul>
     *   <li><b>本方法＝「这个人现在这一屏能看到几条」</b>，所以 <b>R1 与 R2 都要套</b>：
     *       它必须与实际渲染出来的条数<b>一模一样</b>。少套 R1 就会出现 AD-13 首要要防的那种穿帮 ——
     *       「标题写着评论 (5)，往下数只有 4 条」。</li>
     *   <li><b>平台口径 / 互动量统计＝「这条评论对平台是不是一次真实互动」</b>，那一层
     *       <b>R1 隐藏的照常计入</b>（我不看不代表它不存在，它对其他所有人公开可见），
     *       <b>R2 影子的及其回复串一律不计入</b>（它对第三方根本不存在，
     *       计入会让骚扰账号的评论也变成「热度」）。这一层的现成例子是 {@link #countByRealAuthor()}。</li>
     *   <li>评论作者本人视角下数字比他人多 1，是影子机制的固有特性，<b>可接受、不要去「修」</b>（A-A21）。</li>
     * </ul>
     *
     * <p><b>⚠️「父被隐藏 → 整串回复也不计数」（AC4 传递条件，评审三轮 #4）：</b>
     * 回复行（{@code parentId IS NOT NULL}）额外要求其父评论<b>自身可见</b>（父未删 + 对本 viewer 可见 +
     * 不被 R1 拉黑 + 不被 R2 影子）。渲染路径三处（{@code findTopLevel} 过滤父、回复取自已过滤的父、
     * 展开端点判父隐藏）都让父不可见时整串消失；计数少了这一层就会「标题 (3) 下面空空」——
     * AD-13 首要要防的拉黑泄底。同时覆盖父被软删的回复（父不在 {@code findTopLevel} 里，回复也不该计）。
     *
     * <p>⚠️ AD-13 的「评论数」那一条里，「同步套用 R1 + R2」与「R1 隐藏的评论照常计入」写在同一句里，
     * 字面互斥。按<b>该条自己列的 Prevents 首项</b>（防「显示 5 条却只数得出 4 条」）裁定为上面两层的分工，
     * 已回写 {@code CROSS-STORY-DECISIONS.md}。若产品要改成另一种读法，改的是本方法的 R1 那两行 WHERE。
     */
    @Query("""
            SELECT COUNT(c) FROM Comment c
            WHERE c.postId = :postId AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h
                                  WHERE h.holderId = :viewerId AND h.targetId = c.authorId))
              AND ((:hasViewer = true AND c.authorId = :viewerId)
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h2
                                  WHERE h2.holderId = :postAuthorId AND h2.targetId = c.authorId))
              AND (c.parentId IS NULL
                   OR EXISTS (SELECT 1 FROM Comment p
                              WHERE p.id = c.parentId AND p.deletedAt IS NULL
                                AND (p.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                                     OR (:viewerId IS NOT NULL AND p.authorId = :viewerId))
                                AND (:hasViewer = false
                                     OR NOT EXISTS (SELECT 1 FROM UserHideRelation hp
                                                    WHERE hp.holderId = :viewerId AND hp.targetId = p.authorId))
                                AND ((:hasViewer = true AND p.authorId = :viewerId)
                                     OR NOT EXISTS (SELECT 1 FROM UserHideRelation hp2
                                                    WHERE hp2.holderId = :postAuthorId AND hp2.targetId = p.authorId))))
            """)
    long countVisibleForViewer(@Param("postId") long postId,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId,
            @Param("postAuthorId") long postAuthorId);

    /**
     * 一级评论（{@code parent_id IS NULL}）时间正序游标分页（cursor 为 null = 首批），viewer 可见性过滤。
     * 游标比较：{@code (createdAt,id) > (cursorTs,cursorId)}（正序）。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h
                                  WHERE h.holderId = :viewerId AND h.targetId = c.authorId))
              AND ((:hasViewer = true AND c.authorId = :viewerId)
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h2
                                  WHERE h2.holderId = :postAuthorId AND h2.targetId = c.authorId))
              AND (:hasCursor = false
                   OR c.createdAt > :cursorTs
                   OR (c.createdAt = :cursorTs AND c.id > :cursorId))
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findTopLevel(
            @Param("postId") long postId,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") Long cursorId,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId,
            @Param("postAuthorId") long postAuthorId,
            Pageable pageable);

    /**
     * 某一级评论的二级回复时间正序游标分页（展开「查看全部 X 条回复」用），viewer 可见性过滤。
     *
     * <p><b>「父被隐藏 → 整串不展示」（AC4）不在本查询里</b>：本方法只按<b>回复自身作者</b>过滤。
     * 父评论那一层由 {@code CommentQueryService.replies(...)} 在调用前用只读端口判一次并直接返回空页 ——
     * 该分支的父只有一个，在 SQL 里为每一行重复判定同一个父纯属浪费。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h
                                  WHERE h.holderId = :viewerId AND h.targetId = c.authorId))
              AND ((:hasViewer = true AND c.authorId = :viewerId)
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h2
                                  WHERE h2.holderId = :postAuthorId AND h2.targetId = c.authorId))
              AND (:hasCursor = false
                   OR c.createdAt > :cursorTs
                   OR (c.createdAt = :cursorTs AND c.id > :cursorId))
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findReplies(
            @Param("parentId") long parentId,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") Long cursorId,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId,
            @Param("postAuthorId") long postAuthorId,
            Pageable pageable);

    /** 某一级评论的全部未删二级回复（删一级时级联软删用，Story 3.5）。 */
    List<Comment> findByParentIdAndDeletedAtIsNull(long parentId);

    /** 某帖全部未删评论（内容删除级联软删用，Story 3.6）。 */
    List<Comment> findByPostIdAndDeletedAtIsNull(long postId);

    /**
     * 一批一级评论各自的二级回复（首屏内嵌 + replyCount 用），viewer 可见性过滤。
     * 按父分组取正序前 N 在 service 裁。
     *
     * <p><b>本方法<u>无分页</u>（一次取全，service 端裁前 3）</b>，所以 R1/R2 写进 WHERE 之后，
     * service 里的 {@code replies.size()} <b>天然就是过滤后的 replyCount</b>（AC5 第二条口径，AD-13）。
     *
     * <p><b>「父被隐藏 → 整串不展示」（AC4）在这里是自动成立的</b>：{@code parentIds} 取自
     * {@code findTopLevel} <b>已经过滤后</b>的那一页，被 R1/R2 隐藏的父根本不会进来，
     * 它的回复自然也查不到。<b>勿再在本查询里补一层父判定</b>（纯冗余，且会让这条 SQL 无谓变慢）。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId IN :parentIds AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h
                                  WHERE h.holderId = :viewerId AND h.targetId = c.authorId))
              AND ((:hasViewer = true AND c.authorId = :viewerId)
                   OR NOT EXISTS (SELECT 1 FROM UserHideRelation h2
                                  WHERE h2.holderId = :postAuthorId AND h2.targetId = c.authorId))
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findRepliesForParents(@Param("parentIds") List<Long> parentIds,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId,
            @Param("postAuthorId") long postAuthorId);

    /**
     * 注销联动（内容审核 story 9，§5.5.1）：把注销用户仍对他人可见/挂起的评论置 {@code AUTHOR_DEACTIVATED}
     * （非 VISIBLE 即对他人不可见；内容保留 {@code deletedAt IS NULL}）。仅动 VISIBLE/UNDER_REVIEW（幂等）。
     */
    @Modifying
    @Query("""
            UPDATE Comment c
               SET c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.AUTHOR_DEACTIVATED,
                   c.updatedAt = :now
             WHERE c.authorId = :authorId
               AND c.deletedAt IS NULL
               AND c.moderationStatus IN (com.tailtopia.content.domain.CommentModerationStatus.VISIBLE,
                                          com.tailtopia.content.domain.CommentModerationStatus.UNDER_REVIEW)
            """)
    int deactivateByAuthor(@Param("authorId") long authorId, @Param("now") Instant now);

    /** 后台内容管理近评论列表（Story 9.9，含已删软删项）。 */
    java.util.List<Comment> findTop200ByOrderByIdDesc();
}
