package com.tailtopia.content.event;

/**
 * 评论被点赞领域事件（修复线，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 notify 模块推送「赞了你的评论」——<b>content 不直调 notify</b>。
 *
 * <p>只在<b>真正新增</b>一条点赞时发（重复点赞撞唯一约束不发），自赞不发。取消点赞不发、不回退通知（同帖子点赞）。
 *
 * @param postId          评论所属内容 id（通知点击落点）
 * @param commentId       被赞评论 id
 * @param likerId         点赞者 id
 * @param commentAuthorId 评论作者 id（推送目标）
 * @param commentBody     评论原文，只用于推送正文；🔴 <b>禁止写进任何日志</b>
 */
public record CommentLikedEvent(long postId, long commentId, long likerId, long commentAuthorId,
        String commentBody) {
}
