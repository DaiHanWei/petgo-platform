package com.tailtopia.profile.service;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.social.event.PetFollowedEvent;
import com.tailtopia.social.event.UserFollowedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 新手任务①–⑥的记录时机（V1.5.0 onboarding-recall Story 2.1 · FR-176 · OR-1 表）。
 *
 * <table>
 *   <tr><th>任务</th><th>事件</th><th>条件 → 记谁</th></tr>
 *   <tr><td>① FIRST_DIARY</td><td>{@link ContentPublishedEvent}</td><td>{@code type == GROWTH_MOMENT}（公开 / 私密、审核通过补发都算）→ 作者</td></tr>
 *   <tr><td>② FIRST_LIKE_RECEIVED</td><td>{@link ContentLikedEvent}</td><td>{@code likerId != authorId} → 作者（<b>不排除虚拟号 / 自动点赞</b>）</td></tr>
 *   <tr><td>③ FIRST_FOLLOW</td><td>{@link UserFollowedEvent} / {@link PetFollowedEvent}</td><td>→ 关注者（系统自动关注不发事件，天然不算）</td></tr>
 *   <tr><td>④ FIRST_LIKE_GIVEN</td><td>{@link ContentLikedEvent}</td><td>{@code likerId != authorId} → 点赞者</td></tr>
 *   <tr><td>⑤ FIRST_COMMENT</td><td>{@link ContentCommentedEvent}</td><td>一级评论且评论者 ≠ 帖子作者 → 评论者</td></tr>
 *   <tr><td>⑥ FIRST_REPLY</td><td>{@link ContentCommentedEvent}</td><td>回复且被回复人（{@code replyToAuthorId} 优先，否则 {@code parentAuthorId}）存在且 ≠ 评论者 → 评论者</td></tr>
 * </table>
 *
 * <p>🔴 跨线约定：自动点赞（会话 ⑤）同样发 {@link ContentLikedEvent} 且<b>计入</b>②——本监听<b>不得</b>按 automated 过滤。
 *
 * <p>写法：{@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)}；写库只调 {@link NewbieMarkService}
 * （另一个 bean 的 {@code REQUIRES_NEW}）。失败只记 ERROR（userId / task / 异常类名），不重试（V1 姿态）。
 * 日志不记评论原文 / 昵称。
 */
@Component
public class NewbieTaskListener {

    private static final Logger log = LoggerFactory.getLogger(NewbieTaskListener.class);

    private final NewbieMarkService marks;

    public NewbieTaskListener(NewbieMarkService marks) {
        this.marks = marks;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentPublished(ContentPublishedEvent e) {
        if (e.type() == ContentType.GROWTH_MOMENT) {
            mark(e.authorId(), NewbieTask.FIRST_DIARY);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentLiked(ContentLikedEvent e) {
        if (e.likerId() == e.authorId()) {
            return; // 自赞：两项都不记
        }
        mark(e.likerId(), NewbieTask.FIRST_LIKE_GIVEN);
        mark(e.authorId(), NewbieTask.FIRST_LIKE_RECEIVED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentCommented(ContentCommentedEvent e) {
        if (e.parentCommentId() == null) {
            if (e.commenterId() != e.contentAuthorId()) {
                mark(e.commenterId(), NewbieTask.FIRST_COMMENT);
            }
            return;
        }
        Long target = e.replyToAuthorId() != null ? e.replyToAuthorId() : e.parentAuthorId();
        if (target != null && target != e.commenterId()) {
            mark(e.commenterId(), NewbieTask.FIRST_REPLY);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserFollowed(UserFollowedEvent e) {
        mark(e.followerId(), NewbieTask.FIRST_FOLLOW);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPetFollowed(PetFollowedEvent e) {
        mark(e.followerId(), NewbieTask.FIRST_FOLLOW);
    }

    private void mark(long userId, NewbieTask task) {
        try {
            marks.markIfAbsent(userId, task);
        } catch (RuntimeException ex) {
            log.error("newbie task mark failed: userId={} task={} error={}", userId, task.name(),
                    ex.getClass().getSimpleName());
        }
    }
}
