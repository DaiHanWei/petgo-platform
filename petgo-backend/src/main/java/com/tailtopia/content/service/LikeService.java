package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentLike;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.LikeResponse;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容点赞开关（Story 3.4，FR-23）。三类内容均可赞、即时计数、幂等；产出 {@link ContentLikedEvent}
 * 供推送消费（自赞不发事件）。计数实时 {@code COUNT(*)}，**不上 Redis 缓存**（Redis 收窄）。
 */
@Service
public class LikeService {

    private final ContentLikeRepository likes;
    private final ContentPostRepository posts;
    private final ApplicationEventPublisher events;

    public LikeService(ContentLikeRepository likes, ContentPostRepository posts,
            ApplicationEventPublisher events) {
        this.likes = likes;
        this.posts = posts;
        this.events = events;
    }

    /** 点赞（幂等：已赞不叠加、不重复发事件）。返回服务端真值 {liked,likeCount}。 */
    @Transactional
    public LikeResponse like(long postId, long userId) {
        ContentPost post = requireVisible(postId);
        if (!likes.existsByPostIdAndUserId(postId, userId)) {
            try {
                likes.save(ContentLike.of(postId, userId));
                // 仅新点赞产出事件；自赞不发（FR-22B）。
                if (post.getAuthorId() != userId) {
                    events.publishEvent(new ContentLikedEvent(
                            postId, userId, post.getAuthorId(), Instant.now()));
                }
            } catch (DataIntegrityViolationException e) {
                // 并发双点赞撞唯一约束：幂等吞掉，当前态即已赞。
            }
        }
        return new LikeResponse(true, likes.countByPostId(postId));
    }

    /** 取消点赞（幂等：未赞 DELETE 亦成功）。 */
    @Transactional
    public LikeResponse unlike(long postId, long userId) {
        requireVisible(postId);
        likes.deleteByPostIdAndUserId(postId, userId);
        return new LikeResponse(false, likes.countByPostId(postId));
    }

    private ContentPost requireVisible(long postId) {
        return posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> AppException.notFound(ContentDetailService.GONE_DETAIL));
    }
}
