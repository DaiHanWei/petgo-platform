package com.tailtopia.healthreminder.rules;

import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.time.LocalDate;

/**
 * 规则算出的「应有」提醒（V1.5.0 Story 2.1，架构增量 §4.3）。键 = (来源记录, kind, 推送点, 原定日)，宠物隐含。
 * 快照字段供文案 / 落点（2.4）用。
 */
public record DesiredReminder(Long sourceRecordId, ReminderKind kind, PushPoint point, ReminderOrigin origin,
        LocalDate nextDateRef, LocalDate dueDate, VaccinePreset dueVaccinePreset, String dueVaccineName,
        Integer dueDose, DewormType dewormType, String customName) {

    /** 去重键（与 {@code ux_health_reminders_once} 一致，宠物维度由调用方保证）。 */
    public record Key(long sourceRecordId, ReminderKind kind, PushPoint point, LocalDate dueDate) {
    }

    public Key key() {
        return new Key(sourceRecordId == null ? 0L : sourceRecordId, kind, point, dueDate);
    }

    public static DesiredReminder simple(Long sourceRecordId, ReminderKind kind, PushPoint point,
            ReminderOrigin origin, LocalDate nextDateRef, LocalDate dueDate) {
        return new DesiredReminder(sourceRecordId, kind, point, origin, nextDateRef, dueDate, null, null, null, null,
                null);
    }
}
