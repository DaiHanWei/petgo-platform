package com.tailtopia.content.service;

import com.tailtopia.content.event.CommentSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评论异步审核监听（内容审核 story 3 · 异步化）。{@code @Async @TransactionalEventListener(AFTER_COMMIT)}：
 * 评论「先落 {@code UNDER_REVIEW} 秒回」并提交后，异步线程跑阿里云评分路由：
 *
 * <ul>
 *   <li>{@code PASS} → {@link CommentService#approveComment(long)}：转 {@code VISIBLE} + 发新评论事件
 *       （<b>此刻才通知楼主</b>，评论对外可见）。</li>
 *   <li>{@code HIGH_RISK} / {@code DEGRADED}（及理论不可达的 {@code L1_BLOCKED}）/ 审核异常 →
 *       {@link CommentService#queueForReview(long, int)}：fail-closed 入人工队列，绝不自动放行。</li>
 * </ul>
 *
 * <p>L1 硬黑名单已在 {@link CommentService} 创建时同步即时拒绝（不落库），不会到此。
 * <p>独立 bean（非 {@code CommentService} 内自调）确保 {@code @Async} 代理生效；两分支委托
 * {@code CommentService} 的 {@code @Transactional} 方法，AFTER_COMMIT 后各起新事务。
 */
@Component
public class CommentModerationListener {

    private static final Logger log = LoggerFactory.getLogger(CommentModerationListener.class);

    private final ContentModerationService moderation;
    private final CommentService commentService;

    public CommentModerationListener(ContentModerationService moderation, CommentService commentService) {
        this.moderation = moderation;
        this.commentService = commentService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentSubmitted(CommentSubmittedEvent event) {
        CommentVerdict verdict;
        try {
            verdict = moderation.moderateComment(event.body());
        } catch (RuntimeException ex) {
            // fail-closed：审核异常也入队（绝不自动放行）。仅记异常类型，不记评论原文。
            log.warn("评论异步审核异常，fail-closed 入人工队列 commentId={}：{}",
                    event.commentId(), ex.getClass().getSimpleName());
            commentService.queueForReview(event.commentId(), event.contentVersion());
            return;
        }
        if (verdict == CommentVerdict.PASS) {
            commentService.approveComment(event.commentId());
        } else {
            // HIGH_RISK / DEGRADED（及理论不可达的 L1_BLOCKED）→ 人工队列，绝不自动放行。
            commentService.queueForReview(event.commentId(), event.contentVersion());
        }
    }
}
