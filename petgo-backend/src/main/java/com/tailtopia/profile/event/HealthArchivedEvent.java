package com.tailtopia.profile.event;

import java.time.Instant;

/**
 * 问诊结论存档领域事件（Story 8.3，过去式）。{@code HealthEventService.recordDecision} 首次将一条
 * 兽医问诊结论存档进健康事件时发布，驱动里程碑 C-S4「第一次保存兽医问诊结论」自动完成（FR-16，幂等）。
 *
 * @param ownerId      档案所有者 user id
 * @param petProfileId 宠物档案 id
 * @param archivedAt   存档时间（UTC）
 */
public record HealthArchivedEvent(long ownerId, long petProfileId, Instant archivedAt) {
}
