package com.tailtopia.content.repository;

import com.tailtopia.content.domain.Comment;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 评论读取（Story 3.3）。一级时间正序游标分页；二级回复按父正序；计数。写入在 Story 3.5。
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 某帖未删评论总数（detail 的 commentCount，含一级+二级）。 */
    long countByPostIdAndDeletedAtIsNull(long postId);

    /**
     * 一级评论（{@code parent_id IS NULL}）时间正序游标分页（cursor 为 null = 首批）。
     * 游标比较：{@code (createdAt,id) > (cursorTs,cursorId)}（正序）。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL AND c.deletedAt IS NULL
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
            Pageable pageable);

    /**
     * 某一级评论的二级回复时间正序游标分页（展开「查看全部 X 条回复」用）。
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId AND c.deletedAt IS NULL
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
            Pageable pageable);

    /** 某一级评论的全部未删二级回复（删一级时级联软删用，Story 3.5）。 */
    List<Comment> findByParentIdAndDeletedAtIsNull(long parentId);

    /** 某帖全部未删评论（内容删除级联软删用，Story 3.6）。 */
    List<Comment> findByPostIdAndDeletedAtIsNull(long postId);

    /** 一批一级评论各自的前 N 条二级回复（首屏内嵌用，按父分组取正序前 N 在 service 裁）。 */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId IN :parentIds AND c.deletedAt IS NULL
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findRepliesForParents(@Param("parentIds") List<Long> parentIds);
}
