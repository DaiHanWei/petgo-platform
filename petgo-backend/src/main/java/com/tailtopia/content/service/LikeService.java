package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentLike;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.LikeResponse;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentUnlikedEvent;
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

    /** 自动点赞结果：{@code created} = 本次真新建了一行；{@code likeCount} = 写入后的展示赞数（含自动赞）。 */
    public record AutomatedLikeResult(boolean created, long likeCount) {
    }

    /**
     * 自动点赞专用（V1.5.0 likes FR-215 / FR-217）。不做可见性校验（调用方已在同一事务里复查，比
     * {@link #requireVisible} 更严）；只有真新建一行才发事件（{@code automated = true}）。
     * 事件各监听 AFTER_COMMIT 才跑：外层事务回滚 = 无通知、无里程碑（A-AC35）。
     */
    @Transactional
    public AutomatedLikeResult likeAutomated(long postId, long virtualUserId, long authorId) {
        int inserted = likes.insertAutomatedIfAbsent(postId, virtualUserId);
        if (inserted == 1 && virtualUserId != authorId) {
            events.publishEvent(new ContentLikedEvent(postId, virtualUserId, authorId, Instant.now(), true));
        }
        return new AutomatedLikeResult(inserted == 1, likes.countByPostId(postId));
    }

    /**
     * 取消点赞（幂等：未赞 DELETE 亦成功）。真删到行且非自赞时发 {@link ContentUnlikedEvent}
     * （V1.4.0 ① 扣回点赞成长值）；未赞时取消、自赞取消都不发。
     */
    @Transactional
    public LikeResponse unlike(long postId, long userId) {
        ContentPost post = requireVisible(postId);
        int deleted = likes.deleteByPostIdAndUserId(postId, userId);
        if (deleted > 0 && post.getAuthorId() != userId) {
            events.publishEvent(new ContentUnlikedEvent(postId, userId, post.getAuthorId()));
        }
        return new LikeResponse(false, likes.countByPostId(postId));
    }

    private ContentPost requireVisible(long postId) {
        return posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> AppException.notFound(ContentDetailService.GONE_DETAIL));
    }
}
