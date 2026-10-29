package com.tailtopia.content.autocomment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutoCommentLogRepository extends JpaRepository<AutoCommentLog, Long> {

    Optional<AutoCommentLog> findByPostId(long postId);

    /**
     * 本轮候选帖（按发帖时间先到先评）：
     * <ul>
     *   <li>{@code since} 之后发、且已发满最短时长（{@code cutoff} 之前）；</li>
     *   <li>已发布、公开、未删；作者是真实普通用户（虚拟账号 / 管理员的帖不评）；</li>
     *   <li><b>零评论</b>：没有未删的 VISIBLE 或 UNDER_REVIEW 评论——审核中的也算，
     *       否则上一轮刚发、还在审的那条会让本轮再评一次；</li>
     *   <li>没有留档，或留档是 FAILED 且尝试次数未到上限。</li>
     * </ul>
     */
    @Query(value = """
            SELECT p.id FROM content_posts p
            JOIN users u ON u.id = p.author_id
            WHERE p.created_at >= :since AND p.created_at <= :cutoff
              AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL
              AND u.account_type = 'REAL' AND u.role = 'USER' AND u.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.post_id = p.id AND c.deleted_at IS NULL
                              AND c.moderation_status IN ('VISIBLE', 'UNDER_REVIEW'))
              AND NOT EXISTS (SELECT 1 FROM auto_comment_logs l WHERE l.post_id = p.id
                              AND NOT (l.status = 'FAILED' AND l.attempts < :maxAttempts))
            ORDER BY p.created_at, p.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findCandidatePostIds(@Param("since") Instant since, @Param("cutoff") Instant cutoff,
            @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    /** 发出前的复查：AI 生成期间可能已经有人评论了。口径同候选查询的「零评论」。 */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM comments c WHERE c.post_id = :postId AND c.deleted_at IS NULL
                           AND c.moderation_status IN ('VISIBLE', 'UNDER_REVIEW'))
            """, nativeQuery = true)
    boolean hasLiveComment(@Param("postId") long postId);
}
