package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.rules.DesiredReminder;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.util.List;

/**
 * 档案类建议（首次疫苗 / 首次驱虫 / 绝育建议 / 体检）的期望集合扩展点（V1.5.0 Story 2.1 留口，Story 2.2 实现）。
 * 由 {@link HealthReminderRebuilder} 与记录类一并对账（origin=PROFILE 的行也在重建范围内）。
 */
public interface ProfileReminderSource {

    List<DesiredReminder> profileReminders(PetHealthSnapshot pet, List<HealthRecordSnapshot> records,
            ReminderHistory history, LocalDate fromDate);
}
