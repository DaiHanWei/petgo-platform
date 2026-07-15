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
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 某帖未删评论总数（含一级+二级）。<b>已弃用于 detail commentCount</b>，改用 viewer 维度计数。 */
    long countByPostIdAndDeletedAtIsNull(long postId);

    /**
     * 某帖 viewer 可见的未删评论总数（detail 的 commentCount，含一级+二级）。
     * 公开可见（VISIBLE）+ viewer 自己的非可见评论，使渲染列表与计数一致（§5.5，R3 接受轻微 viewer 差异）。
     */
    @Query("""
            SELECT COUNT(c) FROM Comment c
            WHERE c.postId = :postId AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
            """)
    long countVisibleForViewer(@Param("postId") long postId, @Param("viewerId") Long viewerId);

    /**
     * 一级评论（{@code parent_id IS NULL}）时间正序游标分页（cursor 为 null = 首批），viewer 可见性过滤。
     * 游标比较：{@code (createdAt,id) > (cursorTs,cursorId)}（正序）。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
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
            @Param("viewerId") Long viewerId,
            Pageable pageable);

    /**
     * 某一级评论的二级回复时间正序游标分页（展开「查看全部 X 条回复」用），viewer 可见性过滤。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
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
            @Param("viewerId") Long viewerId,
            Pageable pageable);

    /** 某一级评论的全部未删二级回复（删一级时级联软删用，Story 3.5）。 */
    List<Comment> findByParentIdAndDeletedAtIsNull(long parentId);

    /** 某帖全部未删评论（内容删除级联软删用，Story 3.6）。 */
    List<Comment> findByPostIdAndDeletedAtIsNull(long postId);

    /**
     * 一批一级评论各自的二级回复（首屏内嵌 + replyCount 用），viewer 可见性过滤。
     * 按父分组取正序前 N 在 service 裁。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId IN :parentIds AND c.deletedAt IS NULL
              AND (c.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:viewerId IS NOT NULL AND c.authorId = :viewerId))
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findRepliesForParents(@Param("parentIds") List<Long> parentIds,
            @Param("viewerId") Long viewerId);

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
