package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.rules.DesiredReminder;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** Story 2.1 默认：不生成档案类建议（Story 2.2 替换）。 */
@Component
public class EmptyProfileReminderSource implements ProfileReminderSource {

    @Override
    public List<DesiredReminder> profileReminders(PetHealthSnapshot pet, List<HealthRecordSnapshot> records,
            ReminderHistory history, LocalDate fromDate) {
        return List.of();
    }
}
