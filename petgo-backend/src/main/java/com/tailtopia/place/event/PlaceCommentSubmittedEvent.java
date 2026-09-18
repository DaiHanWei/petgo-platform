package com.tailtopia.place.event;

/**
 * 场所评论已提交（V1.3.0 batch-b1 Story 1.7 · AC5）。由 {@code PlaceCommentService.create}
 * 在事务内发出，{@code PlaceCommentModerationListener} 在 AFTER_COMMIT 之后异步消费。
 *
 * <p>🔴 **不带评论正文之外的任何用户标识**（没有 authorId）—— 审核只需要文本；
 * 带上 authorId 只会让它更容易被顺手写进日志（NFR：严禁记录 PII）。
 *
 * <p>⚠️ 与内容评论的 {@code CommentSubmittedEvent} 是**两个事件类**，不复用：
 * 复用的话同一个监听器会收到两种表的 id，而它们的 id 空间完全独立 ——
 * 一次误路由就会把 A 表的审核结果写到 B 表同号的那行上。
 *
 * @param placeCommentId place_comments.id
 * @param body           待审核文本
 * @param contentVersion 入队时捕获的版本（D-CM3 陈旧结果作废；无编辑端点故恒为 1）
 */
public record PlaceCommentSubmittedEvent(long placeCommentId, String body, int contentVersion) {
}
