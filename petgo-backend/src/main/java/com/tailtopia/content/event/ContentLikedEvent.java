package com.tailtopia.content.event;

import java.time.Instant;

/**
 * 内容被点赞领域事件（Story 3.4，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 notify 模块（Epic 6 FR-22B）{@code @Async @EventListener} 消费推送——**content 不直调 notify**。
 *
 * <p>自赞不发此事件（{@code likerId==authorId} 时跳过 publish），满足 FR-22B「自赞不通知」。
 *
 * <p>{@code automated = true}：自动点赞任务（V1.5.0）。除成长值外所有监听照常处理——里程碑、@ 候选、通知、
 * V1.5.0 新手任务 ② 都要计入（PRD 自动点赞 FR-218「What stays counted on purpose」）。
 *
 * @param postId    被赞内容 id
 * @param likerId   点赞者 id
 * @param authorId  内容作者 id（推送目标）
 * @param createdAt 点赞时刻
 * @param automated 是否由自动点赞任务创建（V1.5.0）
 */
public record ContentLikedEvent(long postId, long likerId, long authorId, Instant createdAt, boolean automated) {

    /** 真人点赞（既有调用点与测试沿用，automated = false）。 */
    public ContentLikedEvent(long postId, long likerId, long authorId, Instant createdAt) {
        this(postId, likerId, authorId, createdAt, false);
    }
}
