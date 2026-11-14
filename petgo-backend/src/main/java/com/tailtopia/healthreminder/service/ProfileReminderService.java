package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.rules.DesiredReminder;
import com.tailtopia.healthreminder.rules.PetReminderContext;
import com.tailtopia.healthreminder.rules.ProfileReminderRules;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 档案类建议的期望集合（V1.5.0 Story 2.2 · AC3，实现 2.1 的 {@link ProfileReminderSource}）。用户没有个人上线行
 * （从未被视为 1.5.0 用户）→ 空；normal date 早于该用户上线日的归上线排队。
 */
@Component
public class ProfileReminderService implements ProfileReminderSource {

    private final UserLaunchDao userLaunch;
    private final VetConsultVisitDao consultVisits;

    public ProfileReminderService(UserLaunchDao userLaunch, VetConsultVisitDao consultVisits) {
        this.userLaunch = userLaunch;
        this.consultVisits = consultVisits;
    }

    @Override
    public List<DesiredReminder> profileReminders(PetHealthSnapshot pet, List<HealthRecordSnapshot> records,
            ReminderHistory history, LocalDate fromDate) {
        if (!pet.isCatOrDog()) {
            return List.of();
        }
        LocalDate launch = userLaunch.find(pet.ownerId()).map(UserLaunchDao.UserLaunch::launchDate).orElse(null);
        if (launch == null) {
            return List.of();
        }
        PetReminderContext ctx = new PetReminderContext(pet, records, history, consultVisits.lastVisitDate(pet.petId()));
        return ProfileReminderRules.desired(ctx, launch, fromDate);
    }
}
