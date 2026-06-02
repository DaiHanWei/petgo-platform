package com.petgo.content.repository;

import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.domain.PostStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentPostRepository extends JpaRepository<ContentPost, Long> {

    /** 迷你主页发布数（Story 3.8）：某作者未软删的已发布内容数。 */
    long countByAuthorIdAndDeletedAtIsNullAndStatus(long authorId, PostStatus status);

    /** 成长时间线读：某作者某类型未删内容，createdAt 倒序游标分页（Story 2.4）。 */
    List<ContentPost> findByAuthorIdAndTypeAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
            long authorId, ContentType type, Instant before, Pageable pageable);

    /**
     * 「我的发布」（Story 7.1，FR-36）：当前作者未软删的全部内容（三类混合），keyset 游标倒序。
     */
    @Query("""
            SELECT p FROM ContentPost p
            WHERE p.authorId = :authorId
              AND p.deletedAt IS NULL
              AND p.status = com.petgo.content.domain.PostStatus.PUBLISHED
              AND (:cursorTs IS NULL
                   OR p.createdAt < :cursorTs
                   OR (p.createdAt = :cursorTs AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<ContentPost> findMyPosts(
            @Param("authorId") long authorId,
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
     *   <li>游标：{@code (createdAt,id) < (cursorTs,cursorId)}（cursorTs 为 null = 首批）。</li>
     *   <li>排序：{@code created_at DESC, id DESC}（id tie-breaker 保证游标稳定）。</li>
     * </ul>
     */
    @Query("""
            SELECT p FROM ContentPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.petgo.content.domain.PostStatus.PUBLISHED
              AND (:excludeGrowth = false OR p.type <> com.petgo.content.domain.ContentType.GROWTH_MOMENT)
              AND (:type IS NULL OR p.type = :type)
              AND (:requirePet = false OR p.petId IS NOT NULL)
              AND (:cursorTs IS NULL
                   OR p.createdAt < :cursorTs
                   OR (p.createdAt = :cursorTs AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<ContentPost> findFeed(
            @Param("excludeGrowth") boolean excludeGrowth,
            @Param("type") ContentType type,
            @Param("requirePet") boolean requirePet,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
