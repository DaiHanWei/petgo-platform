package com.tailtopia.social.event;

import java.time.Instant;

/**
 * 追更宠物成功领域事件（V1.4.2 social-topic · Story 1.1 · D-10 / P-3，过去式）。经 {@code ApplicationEventPublisher}
 * 进程内发布，供 notify 模块（Story 1.2）发「有人追更了你的宠物」通知 —— <b>social 不直调 notify</b>。
 *
 * <p>只在追更行<b>真正新建</b>时发布（与宠物计数 +1 同一分支，事务内发布、消费方 AFTER_COMMIT）：
 * 幂等重复追更、任何拒绝、取消追更、拉黑联动、注销清理<b>一律不发</b>。
 * <b>主人拉黑了我</b>也照常发，由通知侧抑制（同 {@link UserFollowedEvent} / D-4）。
 *
 * <p>⚠️ 追更宠物<b>不是</b>「关注一个人」：不发 {@link UserFollowedEvent}（FR-154.1，账号级里程碑不算）。
 *
 * @param followerId   追更者 id（通知的触发人）
 * @param petProfileId 被追更的宠物 id
 * @param ownerId      宠物主人 id（通知接收者）
 * @param followedAt   追更时刻（UTC）
 */
public record PetFollowedEvent(long followerId, long petProfileId, long ownerId, Instant followedAt) {
}
