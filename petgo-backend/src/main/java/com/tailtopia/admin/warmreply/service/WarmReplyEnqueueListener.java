package com.tailtopia.admin.warmreply.service;

import com.tailtopia.content.event.CommentRemovedEvent;
import com.tailtopia.content.event.ContentCommentedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 暖贴回复跟进的入队 / 出队监听（V1.3.0 Story 4.3）。{@code AFTER_COMMIT} + 委托 {@code REQUIRES_NEW} 的服务方法
 * （与 {@code NotificationService.send} 同款：提交后阶段无环境事务，不开新事务写入会静默丢）。
 * <p>D-35 / X-2：只有<b>一级</b>虚拟评论被回复才会入队——两级结构下二级回复的 parentAuthorId / parentCommentId 指向一级，
 * 虚拟账号发的二级被回复识别不出；X-2（reply_to_comment_id）启用后再按目标评论扩展，这里不要「猜」。
 * 日志只记 id，不记评论正文。同一提交阶段的监听按 {@code @Order} 排在最前：AFTER_COMMIT 链上前一个监听抛异常会让后面的被跳过，
 * 本类自吞异常，排最前保证入队不被通知侧的异常连累。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WarmReplyEnqueueListener {

    private static final Logger log = LoggerFactory.getLogger(WarmReplyEnqueueListener.class);

    private final WarmReplyQueueService queue;

    public WarmReplyEnqueueListener(WarmReplyQueueService queue) {
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommented(ContentCommentedEvent e) {
        if (e.parentAuthorId() == null) {
            return; // 一级评论：不是「回复」
        }
        try {
            queue.enqueueIfWarmReply(e);
        } catch (RuntimeException ex) {
            log.warn("warm-reply enqueue failed commentId={} cause={}", e.commentId(), ex.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRemoved(CommentRemovedEvent e) {
        try {
            if (e.parentId() == null) {
                // 一级评论被删 / 下架：若它是有 PENDING 项的虚拟暖评，项整条删除（防孤儿）
                queue.onTopLevelRemoved(e.commentId());
            } else {
                queue.onReplyRemoved(e.commentId(), e.parentId(), e.authorId());
            }
        } catch (RuntimeException ex) {
            log.warn("warm-reply dequeue failed commentId={} cause={}", e.commentId(), ex.getClass().getSimpleName());
        }
    }
}
