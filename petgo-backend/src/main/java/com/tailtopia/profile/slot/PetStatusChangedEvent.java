package com.tailtopia.profile.slot;

import com.tailtopia.profile.domain.PetStatus;

/**
 * 宠物状态实际变化（V1.5.0 健康记录提醒 Story 2.1 · AC5，架构增量 §4.3 触发表）：{@link PetSlotStateService} 在
 * {@code markActive()} / {@code markReadOnly()} 真正改变状态并落库后发布。监听方（健康提醒重建）用 AFTER_COMMIT。
 */
public record PetStatusChangedEvent(long ownerId, long petId, PetStatus newStatus) {
}
