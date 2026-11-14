package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.rules.DesiredReminder;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.healthreminder.rules.ReminderPlanRules;
import com.tailtopia.healthreminder.rules.ReminderReconciler;
import com.tailtopia.healthreminder.rules.SatisfactionLookup;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.LaunchDateProvider;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 整宠重建（V1.5.0 Story 2.1 · AC4，架构增量 §4.3）：按当前记录 + 档案 + 已发历史算出该宠物全部应有的未来提醒，与库里
 * PENDING 行对账（{@link ReminderReconciler}）。粒度是「整只宠物」；只保留 {@code send_date ≥ 下一跑} 的点。
 *
 * <p>独立 bean、{@code REQUIRES_NEW}：监听器在原业务事务提交后调用（写库监听纪律）。同一宠物的重建经
 * {@link HealthReminderLock} 串行。🔒 日志只记 id + 计数。
 */
@Service
public class HealthReminderRebuilder {

    private static final Logger log = LoggerFactory.getLogger(HealthReminderRebuilder.class);

    private final PetProfileRepository pets;
    private final HealthRecordRepository records;
    private final HealthReminderRepository reminders;
    private final LaunchDateProvider launchDates;
    private final ProfileReminderSource profileSource;
    private final HealthReminderAnalytics analytics;
    private final HealthReminderLock lock;

    public HealthReminderRebuilder(PetProfileRepository pets, HealthRecordRepository records,
            HealthReminderRepository reminders, LaunchDateProvider launchDates, ProfileReminderSource profileSource,
            HealthReminderAnalytics analytics, HealthReminderLock lock) {
        this.pets = pets;
        this.records = records;
        this.reminders = reminders;
        this.launchDates = launchDates;
        this.profileSource = profileSource;
        this.analytics = analytics;
        this.lock = lock;
    }

    /** 新事务里重建一只宠物（宠物已删 → 什么都不做）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rebuildPet(long petId, RebuildTrigger trigger) {
        rebuildInCurrentTx(petId, trigger, Instant.now());
    }

    /**
     * 新事务里以给定「当前时刻」重建：日跑 / 上线首跑传当天 00:00 WIB，使下一跑 = 当天（当天的点也排上）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rebuildPetAt(long petId, RebuildTrigger trigger, Instant now) {
        rebuildInCurrentTx(petId, trigger, now);
    }

    /** 在调用方事务里重建（日跑 / 上线首跑批量调用，调用方负责事务）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rebuildInCurrentTx(long petId, RebuildTrigger trigger, Instant now) {
        lock.lockPet(petId);
        Optional<PetProfile> found = pets.findById(petId);
        if (found.isEmpty()) {
            return;
        }
        PetProfile pet = found.get();
        PetHealthSnapshot snap = PetHealthSnapshot.of(pet);
        List<HealthRecordSnapshot> recs = records.findByPetProfileIdOrderByEventDateDescIdDesc(petId).stream()
                .map(HealthRecordSnapshot::of).toList();
        List<HealthReminder> rows = reminders.findByPetProfileId(petId);
        LocalDate fromDate = HealthDates.nextRunDate(now);
        LocalDate today = HealthDates.toWibDate(now);
        ReminderHistory history = ReminderHistory.of(rows);

        List<DesiredReminder> desired = new ArrayList<>(ReminderPlanRules.recordAndEventReminders(snap, recs, history,
                fromDate, launchDates.effectiveLaunchDate(today)));
        if (pet.getStatus() != PetStatus.READ_ONLY) {
            profileSource.profileReminders(snap, recs, history, fromDate).stream()
                    .filter(d -> !d.dueDate().isBefore(fromDate))
                    .forEach(desired::add);
        }

        List<HealthReminder> managed = new ArrayList<>();
        List<HealthReminder> occupied = new ArrayList<>();
        for (HealthReminder r : rows) {
            boolean inScope = r.getStatus() == ReminderStatus.PENDING && r.getOrigin().rebuildManaged()
                    && !r.getSendDate().isBefore(fromDate);
            (inScope ? managed : occupied).add(r);
        }
        ReminderReconciler.Result result = ReminderReconciler.diff(managed, occupied, desired,
                new ReminderReconciler.CancelContext(pet.getStatus() == PetStatus.READ_ONLY, snap.isNeutered(),
                        trigger.deletedRecordId(), SatisfactionLookup.of(recs, snap.petType())));

        for (ReminderReconciler.Cancel c : result.cancels()) {
            c.row().cancel(c.reason(), null);
            reminders.save(c.row());
        }
        // 埋点在提交后发：唯一键冲突回滚后整只重试时不会重复。
        List<ReminderReconciler.Cancel> cancels = result.cancels();
        AfterCommit.run(() -> cancels.forEach(c ->
                analytics.cancelled(c.row(), c.reason(), null, c.satisfiedByRecordId())));
        for (DesiredReminder d : result.inserts()) {
            HealthReminder r = HealthReminder.pending(petId, pet.getOwnerId(), d.sourceRecordId(), d.kind(), d.point(),
                    d.origin(), d.nextDateRef(), d.dueDate());
            r.setSnapshot(d.dueVaccinePreset(), d.dueVaccineName(), d.dueDose(), d.dewormType(), d.customName());
            reminders.save(r);
        }
        for (ReminderReconciler.MarkSatisfied m : result.satisfiedSent()) {
            m.row().markSatisfied(now, m.byRecordId());
            reminders.save(m.row());
        }
        reminders.flush();
        log.info("health reminder rebuild petId={} trigger={} kept={} cancelled={} inserted={} satisfiedSent={}",
                petId, trigger.source(), result.kept().size(), result.cancels().size(), result.inserts().size(),
                result.satisfiedSent().size());
    }
}
