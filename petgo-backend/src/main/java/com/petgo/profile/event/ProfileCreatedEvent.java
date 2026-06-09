package com.petgo.profile.event;

import java.time.Instant;

/**
 * 宠物档案创建领域事件（Story 8.3，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供里程碑自动完成订阅（C-S1 档案创建完成）。roster 物化在 {@code ProfileService.create} 同步完成；
 * 本事件仅驱动 S1 完成（AFTER_COMMIT 异步）。
 *
 * @param ownerId      档案所有者 user id
 * @param petProfileId 宠物档案 id
 * @param createdAt    创建时间（UTC）
 */
public record ProfileCreatedEvent(long ownerId, long petProfileId, Instant createdAt) {
}
