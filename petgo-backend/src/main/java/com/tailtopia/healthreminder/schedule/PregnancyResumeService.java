package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.healthreminder.rules.ReminderPlanRules;
import com.tailtopia.healthreminder.service.AfterCommit;
import com.tailtopia.healthreminder.service.HealthReminderAnalytics;
import com.tailtopia.healthreminder.service.HealthReminderLock;
import com.tailtopia.healthreminder.service.HealthReminderRebuilder;
import com.tailtopia.healthreminder.service.PregnancyResumeHandler;
import com.tailtopia.healthreminder.service.RebuildTrigger;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.LaunchDateProvider;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 怀孕 / 哺乳清除后的恢复处理（V1.5.0 Story 2.3 · AC5，实现 2.1 的 {@link PregnancyResumeHandler}）：按 {@link ResumeRules}
 * 插补发、取消同 Next date 其余点，再以 R 为「下一跑」普通重建其余部分。幂等（同一 Next date 补发过就不再补）。
 */
@Service
public class PregnancyResumeService implements PregnancyResumeHandler {

    private static final Logger log = LoggerFactory.getLogger(PregnancyResumeService.class);

    private final PetProfileRepository pets;
    private final HealthRecordRepository records;
    private final HealthReminderRepository reminders;
    private final HealthReminderRebuilder rebuilder;
    private final HealthReminderAnalytics analytics;
    private final HealthReminderLock lock;
    private final LaunchDateProvider launchDates;

    public PregnancyResumeService(PetProfileRepository pets, HealthRecordRepository records,
            HealthReminderRepository reminders, HealthReminderRebuilder rebuilder, HealthReminderAnalytics analytics,
            HealthReminderLock lock, LaunchDateProvider launchDates) {
        this.pets = pets;
        this.records = records;
        this.reminders = reminders;
        this.rebuilder = rebuilder;
        this.analytics = analytics;
        this.lock = lock;
        this.launchDates = launchDates;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPregnancyCleared(long petId, Instant clearedAt) {
        lock.lockPet(petId);
        PetProfile pet = pets.findById(petId).orElse(null);
        if (pet == null) {
            return;
        }
        LocalDate resumeDate = HealthDates.nextRunDate(clearedAt);
        PetHealthSnapshot snap = PetHealthSnapshot.of(pet);
        List<HealthRecordSnapshot> recs = records.findByPetProfileIdOrderByEventDateDescIdDesc(petId).stream()
                .map(HealthRecordSnapshot::of).toList();
        List<HealthReminder> rows = reminders.findByPetProfileId(petId);
        List<HealthReminder> pending = rows.stream().filter(r -> r.getStatus() == ReminderStatus.PENDING).toList();
        ResumeRules.Plan plan = ResumeRules.plan(snap, recs, ReminderHistory.of(rows), pending, resumeDate,
                launchDates.effectiveLaunchDate(HealthDates.toWibDate(clearedAt)));
        for (HealthReminder r : plan.cancelCatchUpSent()) {
            r.cancel(CancelReason.CATCH_UP_SENT, null);
            reminders.save(r);
            AfterCommit.run(() -> analytics.cancelled(r, CancelReason.CATCH_UP_SENT, null, null));
        }
        for (ResumeRules.CatchUp c : plan.catchUps()) {
            HealthReminder r = HealthReminder.pending(petId, pet.getOwnerId(), c.record().id(),
                    ReminderKind.VACCINE_D_PLUS_7, PushPoint.RESUME_CATCH_UP, ReminderOrigin.RESUME_CATCH_UP,
                    c.nextDate(), c.sendDate());
            ReminderPlanRules.VaccineSnapshot v = ReminderPlanRules.vaccineSnapshot(snap, c.record(), recs);
            r.setSnapshot(v.preset(), v.name(), v.dose(), null, null);
            reminders.save(r);
        }
        reminders.flush();
        // 其余部分以 R 为下一跑普通重建（已补发的 Next date 由历史压住，不再生成点）。
        rebuilder.rebuildInCurrentTx(petId, RebuildTrigger.of("pregnancy_cleared"),
                resumeDate.atTime(HealthDates.DAILY_RUN_TIME.minusMinutes(1))
                        .atZone(ScheduleWindow.WIB).toInstant());
        log.info("health pregnancy resume petId={} catchUps={} cancelled={}", petId, plan.catchUps().size(),
                plan.cancelCatchUpSent().size());
    }
}
