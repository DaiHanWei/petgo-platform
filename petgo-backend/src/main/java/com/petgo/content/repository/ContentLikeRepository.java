package com.petgo.content.repository;

import com.petgo.content.domain.ContentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点赞读写（Story 3.4）。计数实时 {@code COUNT(*)}（V1 不上缓存）；唯一约束兜底防重。
 */
public interface ContentLikeRepository extends JpaRepository<ContentLike, Long> {

    boolean existsByPostIdAndUserId(long postId, long userId);

    long countByPostId(long postId);

    @Transactional
    void deleteByPostIdAndUserId(long postId, long userId);

    /** 内容删除级联：物理清该帖全部点赞（点赞无保留价值，Story 3.6）。 */
    @Transactional
    void deleteByPostId(long postId);
}
