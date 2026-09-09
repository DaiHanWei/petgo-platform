package com.tailtopia.content.event;

import java.time.Instant;

/**
 * 内容被评论领域事件（Story 3.5，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 notify 模块（Epic 6 FR-22B）消费推送——**content 不直调 notify**。
 *
 * <p>携带足够信息供 notify 侧去重 + 排除自评：内容作者、被回复的一级作者（一级评论时为 null）。
 * 自评不通知由 notify 侧按 {@code commenterId} 排除（事件本身仍发，携带全量 id）。
 *
 * @param postId           被评论内容 id
 * @param commentId        新评论 id
 * @param commenterId      评论者 id
 * @param contentAuthorId  内容作者 id（推送目标之一）
 * @param parentAuthorId   被回复的一级评论作者 id（一级评论时 null）
 */
public record ContentCommentedEvent(
        long postId,
        long commentId,
        long commenterId,
        long contentAuthorId,
        Long parentAuthorId,
        Instant createdAt,
        /** V1.3.0 Story 4.3：二级回复所挂的一级评论 id（一级评论为 null）；暖贴跟进入队按它定位被回复的虚拟一级评论。 */
        Long parentCommentId) {
}
