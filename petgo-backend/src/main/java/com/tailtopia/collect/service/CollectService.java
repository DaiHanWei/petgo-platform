package com.tailtopia.collect.service;

import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.content.event.ContentUncollectedEvent;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentDetailService;
import com.tailtopia.shared.error.AppException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 帖子收藏（V1.4.0 ④ Story 2.1 · AD-8 · P-2 / P-7）。
 *
 * <p>🔴 本线<b>只发事件</b>：不写任何成长值逻辑、不监听这两个事件、<b>不发通知</b>（PRD：被收藏不通知作者）——
 * 构造器里没有 {@code NotificationService} 就是证明。事件是本线唯一的发布方。
 *
 * <p>🔴 收藏 / 取消必须 {@code @Transactional}：① 的成长值监听是 AFTER_COMMIT，没有事务事件不会触发、静默不入账。
 */
@Service
public class CollectService {

    private final ContentDetailService detail;
    private final ContentPostRepository posts;
    private final ContentCollectionRepository collections;
    private final CollectVisibility visibility;
    private final ApplicationEventPublisher events;

    public CollectService(ContentDetailService detail, ContentPostRepository posts,
            ContentCollectionRepository collections, CollectVisibility visibility,
            ApplicationEventPublisher events) {
        this.detail = detail;
        this.posts = posts;
        this.collections = collections;
        this.visibility = visibility;
        this.events = events;
    }

    /**
     * 收藏。可见性同详情（打得开才能收藏；不可见统一 404）→ 私密帖 422 → 写入（幂等）。
     * 顺序固定「先 404 后 422」：不可见的私密帖不泄漏「它是私密的」。
     *
     * <p>只在<b>真正新增</b>且此刻对收藏人可见（双向口径，见 {@link CollectVisibility}）时发
     * {@link ContentCollectedEvent}；收藏人 = 作者也发（① 自行不入账）。
     */
    @Transactional
    public boolean collect(long postId, long me) {
        ContentPost post = detail.requireVisiblePost(postId, me);
        if (post.getVisibility() == ContentVisibility.PRIVATE) {
            throw AppException.collectNotPublic("只能收藏公开内容");
        }
        if (collections.insertIfAbsent(me, postId) == 1 && visibility.isVisibleTo(me, post)) {
            events.publishEvent(new ContentCollectedEvent(postId, me, post.getAuthorId()));
        }
        return true;
    }

    /**
     * 取消收藏。<b>不要求可见</b>、<b>永不 404</b>（用户要能移除不可见条目；对任何 id 结果一致，不泄漏存在性）。
     *
     * <p>P-7：删到 1 行且帖子此刻对我可见 → 发 {@link ContentUncollectedEvent}；不可见 / 本就没收藏 → 不发。
     */
    @Transactional
    public boolean uncollect(long postId, long me) {
        ContentPost post = posts.findById(postId).orElse(null); // 含软删，不过滤
        int n = collections.deleteByUserIdAndPostId(me, postId);
        if (n == 1 && post != null && visibility.isVisibleTo(me, post)) {
            events.publishEvent(new ContentUncollectedEvent(postId, me, post.getAuthorId()));
        }
        return false;
    }
}
