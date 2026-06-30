package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentPostRepository extends JpaRepository<ContentPost, Long>, ContentPostAdminSearch {

    /** 迷你主页发布数（Story 3.8）：某作者未软删的已发布内容数。 */
    long countByAuthorIdAndDeletedAtIsNullAndStatus(long authorId, PostStatus status);

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

    /** 名片快乐时刻流（Story 2.6 AC7 · F9）：某作者某类型未删内容，按 event_date 倒序取最近 N。 */
    List<ContentPost> findByAuthorIdAndTypeAndDeletedAtIsNullOrderByEventDateDescCreatedAtDesc(
            long authorId, ContentType type, Pageable pageable);

    /** 统计：某作者某类型未删且已发布内容数（Story 2.4 AC5 统计栏快乐时刻数）。 */
    long countByAuthorIdAndTypeAndDeletedAtIsNullAndStatus(
            long authorId, ContentType type, PostStatus status);

    /**
     * 「我的发布」（Story 7.1，FR-36）：当前作者未软删的全部内容（三类混合），keyset 游标倒序。
     */
    @Query("""
            SELECT p FROM ContentPost p
            WHERE p.authorId = :authorId
              AND p.deletedAt IS NULL
              AND p.status = com.tailtopia.content.domain.PostStatus.PUBLISHED
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
     *   <li>公开口径：{@code deleted_at IS NULL AND status=PUBLISHED}。</li>
     *   <li>硬过滤（B 状态）：{@code excludeGrowth=true} → 排除 GROWTH_MOMENT（后端权威 WHERE）。</li>
     *   <li>分类：{@code type} 非空则精确过滤；{@code requirePet=true}（成长日历分类）→ pet_id 非空。</li>
     *   <li>游标：{@code (createdAt,id) < (cursorTs,cursorId)}（{@code hasCursor=false} = 首批）。
     *       用布尔标志而非裸 {@code :cursorTs IS NULL}：后者令 PG 无法推断 NULL 参数类型
     *       （42P18 could not determine data type）；此式下 cursorTs 仅与 createdAt 比较即可定型。</li>
     *   <li>排序：{@code created_at DESC, id DESC}（id tie-breaker 保证游标稳定）。</li>
     * </ul>
     */
    @Query("""
            SELECT p FROM ContentPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.tailtopia.content.domain.PostStatus.PUBLISHED
              AND (:excludeGrowth = false OR p.type <> com.tailtopia.content.domain.ContentType.GROWTH_MOMENT)
              AND (:type IS NULL OR p.type = :type)
              AND (:requirePet = false OR p.petId IS NOT NULL)
              AND (:hasCursor = false
                   OR p.createdAt < :cursorTs
                   OR (p.createdAt = :cursorTs AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<ContentPost> findFeed(
            @Param("excludeGrowth") boolean excludeGrowth,
            @Param("type") ContentType type,
            @Param("requirePet") boolean requirePet,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
