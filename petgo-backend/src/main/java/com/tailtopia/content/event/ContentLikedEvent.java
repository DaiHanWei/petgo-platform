package com.tailtopia.content.event;

import java.time.Instant;

/**
 * 内容被点赞领域事件（Story 3.4，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 notify 模块（Epic 6 FR-22B）{@code @Async @EventListener} 消费推送——**content 不直调 notify**。
 *
 * <p>自赞不发此事件（{@code likerId==authorId} 时跳过 publish），满足 FR-22B「自赞不通知」。
 *
 * @param postId   被赞内容 id
 * @param likerId  点赞者 id
 * @param authorId 内容作者 id（推送目标）
 */
public record ContentLikedEvent(long postId, long likerId, long authorId, Instant createdAt) {
}
