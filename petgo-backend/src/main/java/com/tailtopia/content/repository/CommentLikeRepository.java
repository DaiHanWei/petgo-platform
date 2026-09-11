package com.tailtopia.content.repository;

import com.tailtopia.content.domain.CommentLike;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 评论点赞仓库（V1.3.0 批次 A · Story 2.4 · AD-A7）。
 *
 * <p>🔴 <b>取数一律批量</b>（AC7 / 继承 v1.1.6 AD-7 的口径）：一页 20 条评论逐条查点赞数
 * 就是 20 次查询，逐条查「我赞没赞」又是 20 次。本接口**刻意不提供任何逐条方法**
 * —— 拿不到逐条版本，就写不出 N+1。
 *
 * <p>⚠️ 没有 {@code deleteByUserId}：注销走「就地匿名化 user 行、不物理删」（决策 D1/A），
 * 与 {@code content_likes} 完全相同。给本表单独加删除逻辑就是另立了一套口径（AC8）。
 */
public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    /** 幂等/取消用：单条存在性与删除（作用于**一个**评论+用户，不是批量场景）。 */
    boolean existsByCommentIdAndUserId(long commentId, long userId);

    long deleteByCommentIdAndUserId(long commentId, long userId);

    /**
     * 一批评论各自的点赞数（AC7）。
     *
     * <p>无人点赞的评论**不在结果里**，调用方默认 0 —— 不要为了「结果齐整」在 SQL 里外连接补零，
     * 那会把索引用不上。
     */
    @Query("""
            SELECT l.commentId AS commentId, COUNT(l) AS likeCount FROM CommentLike l
            WHERE l.commentId IN :commentIds
            GROUP BY l.commentId
            """)
    List<CommentLikeCount> countByCommentIdIn(@Param("commentIds") Collection<Long> commentIds);

    /** 这一批评论里，**该用户**赞过的那些 id（AC7）。游客不调本方法。 */
    @Query("SELECT l.commentId FROM CommentLike l "
            + "WHERE l.commentId IN :commentIds AND l.userId = :userId")
    List<Long> findLikedCommentIds(@Param("commentIds") Collection<Long> commentIds,
            @Param("userId") long userId);

    /** 批量点赞数投影（commentId → 条数）。 */
    interface CommentLikeCount {
        Long getCommentId();

        long getLikeCount();
    }
}
