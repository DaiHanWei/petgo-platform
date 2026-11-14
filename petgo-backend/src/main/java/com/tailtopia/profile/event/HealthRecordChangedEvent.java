package com.tailtopia.profile.event;

/**
 * 健康记录增 / 改 / 删（V1.5.0 健康记录提醒 Story 1.1，架构增量 §4.3 触发表）。在 {@code HealthRecordService}
 * 的写事务内发布；监听方（提醒重建，Story 2.1）一律 {@code @TransactionalEventListener(AFTER_COMMIT)}。
 *
 * <p>{@code reminderRelevant}：新建 / 删除恒 true；编辑时 = 除 note 外任一字段实际变化（只改备注不触发重建，PRD 字面）。
 * 与里程碑用的 {@link HealthRecordCreatedEvent} 互不替代，两者都发。🔒 只带 id，不带任何健康字段。
 */
public record HealthRecordChangedEvent(long ownerId, long petId, long recordId, Change change,
        boolean reminderRelevant) {

    public enum Change {
        CREATED,
        UPDATED,
        DELETED
    }
}
