package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentLike;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点赞读写（Story 3.4）。计数实时 {@code COUNT(*)}（V1 不上缓存）；唯一约束兜底防重。
 */
public interface ContentLikeRepository extends JpaRepository<ContentLike, Long> {

    boolean existsByPostIdAndUserId(long postId, long userId);

    long countByPostId(long postId);

    /** 批量点赞数（Feed 卡片 likeCount，PRD-642）：一次 GROUP BY 取一页帖子的赞数，避免 N+1。 */
    @Query("SELECT l.postId AS postId, COUNT(l) AS likeCount FROM ContentLike l "
            + "WHERE l.postId IN :postIds GROUP BY l.postId")
    List<PostLikeCount> countByPostIdIn(@Param("postIds") Collection<Long> postIds);

    /** 批量点赞数投影（postId → 赞数）。无赞的帖不在结果中，调用方默认 0。 */
    interface PostLikeCount {
        Long getPostId();

        long getLikeCount();
    }

    @Transactional
    void deleteByPostIdAndUserId(long postId, long userId);

    /** 内容删除级联：物理清该帖全部点赞（点赞无保留价值，Story 3.6）。 */
    @Transactional
    void deleteByPostId(long postId);
}
