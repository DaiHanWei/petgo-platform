package com.tailtopia.social.event;

import java.time.Instant;

/**
 * 关注成功领域事件（V1.3.4 follow · Story 3.2 · AD-4，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 notify 模块发「新粉丝」通知 —— <b>social 不直调 notify</b>（同 {@code ContentLikedEvent} 范式）。
 *
 * <p>只在关注行<b>真正新建</b>时发布（与两端计数 +1 同一分支）：幂等重复关注、任何拒绝、取关、拉黑联动取关、
 * 注销清理<b>一律不发</b>（FR-140.12）。<b>对方拉黑了我</b>也照常发（D-4），由通知侧抑制，不在关注结果上暴露。
 *
 * @param followerId 关注者 id（通知的触发人）
 * @param followeeId 被关注者 id（通知接收者）
 * @param followedAt 关注时刻（UTC）
 */
public record UserFollowedEvent(long followerId, long followeeId, Instant followedAt) {
}
