package com.petgo.content.event;

import java.time.Instant;

/**
 * 内容被运营下架领域事件（Story 3.7 AC3，过去式）。仅在 <b>ADMIN_TAKEDOWN</b> 软删时发布，
 * 经 {@code ApplicationEventPublisher} 进程内发布，供 notify 模块 {@code @TransactionalEventListener}
 * 消费推送作者「你发布的内容因违反社区规范已被移除」——<b>content 不直调 notify</b>。
 *
 * <p>护栏：作者自删（AUTHOR_DELETE）<b>不</b>发此事件；通知<b>不说明举报人</b>、V1 <b>无申诉入口</b>；
 * 驳回（DISMISSED）静默保留、不发任何事件。
 *
 * @param postId    被下架内容 id（内容已 404，仅作内部回查标识）
 * @param authorId  内容作者 id（推送目标）
 * @param removedAt 下架时刻（UTC）
 */
public record ContentRemovedEvent(long postId, long authorId, Instant removedAt) {
}
