package com.tailtopia.content.event;

/**
 * 评论已提交待异步审核（内容审核 story 3 · 异步化）。评论先落 {@code UNDER_REVIEW} 并提交后发布，
 * 由 {@code CommentModerationListener}（{@code @Async @TransactionalEventListener(AFTER_COMMIT)}）消费：
 * 跑阿里云评分 → PASS 转 VISIBLE + 发新评论事件（此刻才通知楼主）；高危/降级 → 入人工队列。
 *
 * <p>{@code body} 供异步审核评分（不落日志）；{@code contentVersion} 供入队时携带版本（陈旧作废语义）。
 * L1 硬黑名单在创建时已同步即时拒绝，不会到此。
 */
public record CommentSubmittedEvent(long commentId, String body, int contentVersion) {
}
