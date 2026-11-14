package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.rules.PetReminderContext;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.repository.HealthRecordRepository;
import org.springframework.stereotype.Component;

/** 读一只宠物的 {@link PetReminderContext}（快照 + 记录 + 提醒历史 + 最近问诊到访）。 */
@Component
public class PetContextLoader {

    private final HealthRecordRepository records;
    private final HealthReminderRepository reminders;
    private final VetConsultVisitDao consultVisits;

    public PetContextLoader(HealthRecordRepository records, HealthReminderRepository reminders,
            VetConsultVisitDao consultVisits) {
        this.records = records;
        this.reminders = reminders;
        this.consultVisits = consultVisits;
    }

    public PetReminderContext load(PetProfile pet) {
        long petId = pet.getId();
        return new PetReminderContext(PetHealthSnapshot.of(pet),
                records.findByPetProfileIdOrderByEventDateDescIdDesc(petId).stream()
                        .map(HealthRecordSnapshot::of).toList(),
                ReminderHistory.of(reminders.findByPetProfileId(petId)), consultVisits.lastVisitDate(petId));
    }
}
