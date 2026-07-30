package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentPostRepository extends JpaRepository<ContentPost, Long>, ContentPostAdminSearch {

    /** 迷你主页发布数（Story 3.8）：某作者未软删的已发布内容数。 */
    long countByAuthorIdAndDeletedAtIsNullAndStatus(long authorId, PostStatus status);

    /**
     * 删除宠物档案前解绑其成长帖（bug 20260702-237 / 决策 F18）：把引用该 pet 的 content_posts.pet_id 置 NULL。
     * FK fk_content_posts_pet 无 ON DELETE，直接删 pet 会被引用阻断；置空保留帖子本体（UGC 保留），
     * 仅解除与已删宠物的绑定（pet_id 可空，仅 GROWTH_MOMENT 曾绑定）。返回受影响行数。
     */
    @Modifying
    @Query("update ContentPost p set p.petId = null where p.petId = :petId")
    int detachPet(@Param("petId") long petId);

    /** 后台用户详情（Story 3.1）：某作者全部内容（含已软删，运营视角），createdAt 倒序。 */
    List<ContentPost> findByAuthorIdOrderByCreatedAtDesc(long authorId);

    /** 成长时间线读：某作者某类型未删内容，createdAt 倒序游标分页（Story 2.4）。 */
    List<ContentPost> findByAuthorIdAndTypeAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
            long authorId, ContentType type, Instant before, Pageable pageable);

    /** 日历月度聚合（Story 2.4 R2 · F9）：某作者某类型未删内容，event_date 落 [from,to]，按 event_date 升、created_at 升。 */
    List<ContentPost> findByAuthorIdAndTypeAndDeletedAtIsNullAndEventDateBetweenOrderByEventDateAscCreatedAtAsc(
            long authorId, ContentType type, LocalDate from, LocalDate to);

    /** 当天详情（Story 2.4 R2 · F9）：某作者某类型未删内容，指定 event_date，按 created_at 升序（正序）。 */
    List<ContentPost> findByAuthorIdAndTypeAndDeletedAtIsNullAndEventDateOrderByCreatedAtAsc(
            long authorId, ContentType type, LocalDate eventDate);

    /**
     * 名片快乐时刻流（Story 2.6 AC7 · F9）：某作者某类型未删内容，按 event_date 倒序取最近 N。
     * 内容审核 story 2（§5.4）：叠加 {@code status} 过滤——公开/名片路径传 {@code PUBLISHED}，挂起零泄漏。
     */
    List<ContentPost> findByAuthorIdAndTypeAndDeletedAtIsNullAndStatusOrderByEventDateDescCreatedAtDesc(
            long authorId, ContentType type, PostStatus status, Pageable pageable);

    /** 统计：某作者某类型未删且已发布内容数（Story 2.4 AC5 统计栏快乐时刻数）。 */
    long countByAuthorIdAndTypeAndDeletedAtIsNullAndStatus(
            long authorId, ContentType type, PostStatus status);

    /**
     * 「我的发布」（Story 7.1，FR-36）：当前作者未软删的全部内容（三类混合），keyset 游标倒序。
     *
     * <p><b>可见性（内容审核 story 2 · D-CM2 核心）</b>：放行作者本人的 {@code PUBLISHED} + {@code UNDER_REVIEW}
     * ——挂起帖仅作者本人可见（本查询已按 {@code authorId} 收口 + {@code deletedAt IS NULL}，加入 UNDER_REVIEW
     * 不泄漏）。Feed（{@link #findFeed}）保持仅 PUBLISHED，他人零可见。
     */
    @Query("""
            SELECT p FROM ContentPost p
            WHERE p.authorId = :authorId
              AND p.deletedAt IS NULL
              AND (p.status = com.tailtopia.content.domain.PostStatus.PUBLISHED
                   OR p.status = com.tailtopia.content.domain.PostStatus.UNDER_REVIEW)
              AND (:hasCursor = false
                   OR p.createdAt < :cursorTs
                   OR (p.createdAt = :cursorTs AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<ContentPost> findMyPosts(
            @Param("authorId") long authorId,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * Feed 读取（Story 3.2）：全平台公开内容时间倒序游标分页，叠加宠物状态硬过滤 + 分类过滤。
     *
     * <ul>
     *   <li>可见性：{@code deleted_at IS NULL} 且（{@code status=PUBLISHED} <b>或</b> 作者本人的 {@code UNDER_REVIEW}
     *       挂起帖，即 {@code hasViewer=true AND authorId=viewerId}）。挂起帖对作者无感知（照常出现在首页），
     *       对他人零泄漏（仅 PUBLISHED）；拒绝即软删（{@code deletedAt}），对所有人（含作者）隐藏。</li>
     *   <li>硬过滤（B 状态）：{@code excludeGrowth=true} → 排除 GROWTH_MOMENT（后端权威 WHERE）。</li>
     *   <li>分类：{@code type} 非空则精确过滤；{@code requirePet=true}（成长日历分类）→ pet_id 非空。</li>
     *   <li>游标：{@code (createdAt,id) < (cursorTs,cursorId)}（{@code hasCursor=false} = 首批）。
     *       用布尔标志而非裸 {@code :cursorTs IS NULL}：后者令 PG 无法推断 NULL 参数类型
     *       （42P18 could not determine data type）；此式下 cursorTs 仅与 createdAt 比较即可定型。</li>
     *   <li>举报者隐藏（内容审核 cm-6 §5.4）：{@code hasViewer=true}（登录）→ 排除「当前查看者已举报的帖」
     *       （相关子查询命中 {@code uq_content_reports_reporter_post}）；{@code hasViewer=false}（游客）→ 不过滤。
     *       同样用布尔标志门控，避免游客传 NULL viewerId 触发 42P18（{@code :viewerId} 虽与 bigint 列比较可定型，
     *       仍沿用 findFeed 既有判空惯例保持一致）。</li>
     *   <li>排序：{@code created_at DESC, id DESC}（id tie-breaker 保证游标稳定）。</li>
     * </ul>
     */
    @Query("""
            SELECT p FROM ContentPost p
            WHERE p.deletedAt IS NULL
              AND (p.status = com.tailtopia.content.domain.PostStatus.PUBLISHED
                   OR (p.status = com.tailtopia.content.domain.PostStatus.UNDER_REVIEW
                       AND :hasViewer = true AND p.authorId = :viewerId))
              AND (:excludeGrowth = false OR p.type <> com.tailtopia.content.domain.ContentType.GROWTH_MOMENT)
              AND (:type IS NULL OR p.type = :type)
              AND (:requirePet = false OR p.petId IS NOT NULL)
              AND (:hasViewer = false
                   OR NOT EXISTS (SELECT 1 FROM ContentReport r
                                  WHERE r.postId = p.id AND r.reporterId = :viewerId))
              AND (:hasCursor = false
                   OR p.createdAt < :cursorTs
                   OR (p.createdAt = :cursorTs AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<ContentPost> findFeed(
            @Param("excludeGrowth") boolean excludeGrowth,
            @Param("type") ContentType type,
            @Param("requirePet") boolean requirePet,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * 注销联动（内容审核 story 9，§5.5.1）：把注销用户仍在公开/挂起口径的帖子置 {@code AUTHOR_DEACTIVATED}
     * （对他人隐藏；内容保留 {@code deletedAt IS NULL}，与匿名化并存）。仅动 PUBLISHED/UNDER_REVIEW（幂等）。
     */
    @Modifying
    @Query("""
            UPDATE ContentPost p
               SET p.status = com.tailtopia.content.domain.PostStatus.AUTHOR_DEACTIVATED, p.updatedAt = :now
             WHERE p.authorId = :authorId
               AND p.deletedAt IS NULL
               AND p.status IN (com.tailtopia.content.domain.PostStatus.PUBLISHED,
                                com.tailtopia.content.domain.PostStatus.UNDER_REVIEW)
            """)
    int deactivateByAuthor(@Param("authorId") long authorId, @Param("now") Instant now);
}
