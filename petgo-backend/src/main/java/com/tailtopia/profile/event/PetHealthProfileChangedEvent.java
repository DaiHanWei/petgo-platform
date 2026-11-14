package com.tailtopia.profile.event;

import java.time.Instant;

/**
 * 宠物档案里与健康提醒相关的字段变化（V1.5.0 健康记录提醒 Story 1.1，架构增量 §4.3 触发表）：生日、性别、绝育状态、
 * 怀孕 / 哺乳。由 {@code ProfileService.update}（以及保存 NEUTER 记录把绝育状态置 NEUTERED 时的
 * {@code HealthRecordService}）在写事务内发布；监听方一律 AFTER_COMMIT。
 *
 * <p>{@code pregnantSetYes} = 本次设为「是」（含 true → true 刷新）；{@code pregnantCleared} = 由「是」变为「否」
 * （用户改，或 Story 1.3 的自动清除，此时 {@code changedAt} = 当天 00:00 WIB）。🔒 不带任何健康字段值。
 */
public record PetHealthProfileChangedEvent(long ownerId, long petId, boolean birthdayChanged, boolean sexChanged,
        boolean neuterChanged, boolean pregnantSetYes, boolean pregnantCleared, Instant changedAt) {
}
