package com.tailtopia.content.event;

import java.time.Instant;

/**
 * 评论<b>不再可见</b>领域事件（V1.3.0 Story 4.3，过去式）：作者自删（含一级级联软删的每条二级，各发一次）或运营下架
 * （{@code takedownComment} 成功路径）。{@code rejectComment}（从未可见）与 {@code restoreComment}（恢复不是「新回复」）<b>不发</b>。
 *
 * <h2>为什么不复用帖子级事件</h2>
 * {@link ContentRemovedEvent} / {@link ContentUnavailableEvent} 都是<b>帖子</b>级；评论删除 / 下架此前没有任何事件。
 * 首个消费者是暖贴回复跟进队列的出队（D-19：真实回复被删 / 下架 → 待跟进计数减一，归零删项）。事务内发布，消费方按需 AFTER_COMMIT。
 *
 * @param commentId 被删 / 下架的评论 id
 * @param postId    所属帖子
 * @param authorId  评论作者
 * @param parentId  一级父评论 id（一级评论为 null）
 * @param reason    原因
 * @param at        发生时刻（UTC）
 */
public record CommentRemovedEvent(long commentId, long postId, long authorId, Long parentId, CommentRemovedReason reason, Instant at) {
}
