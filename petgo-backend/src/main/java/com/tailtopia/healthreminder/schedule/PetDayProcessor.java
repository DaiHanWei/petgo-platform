package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.rules.PetReminderContext;
import com.tailtopia.healthreminder.service.AfterCommit;
import com.tailtopia.healthreminder.service.HealthReminderAnalytics;
import com.tailtopia.healthreminder.service.HealthReminderLock;
import com.tailtopia.healthreminder.service.PetContextLoader;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.health.PregnancyFlag;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日跑里一只宠物一个事务（V1.5.0 Story 2.3 · AC1.3）：取当天 PENDING 行 → {@link HealthReminderPlanner#decide} → 落取消 /
 * 顺延 / 挂起 → 经 {@link HealthDispatchPort} 投递。同日重跑只会看到仍 PENDING 的行（已发的不再动）。
 */
@Service
public class PetDayProcessor {

    /** 一只宠物当天的结果。 */
    public record PetDayResult(int groupsSent, Map<CancelReason, Integer> cancelled) {

        static PetDayResult empty() {
            return new PetDayResult(0, new EnumMap<>(CancelReason.class));
        }
    }

    /** 当天这只宠物 / 主人的占用。 */
    public record Occupancy(boolean scheduledPushToday, boolean recallToday, Set<LocalDate> futureScheduledPushDays,
            Set<LocalDate> futureRecallDays) {
    }

    private final PetProfileRepository pets;
    private final HealthReminderRepository reminders;
    private final PetContextLoader contexts;
    private final HealthDispatchPort dispatcher;
    private final HealthReminderAnalytics analytics;
    private final HealthReminderLock lock;

    public PetDayProcessor(PetProfileRepository pets, HealthReminderRepository reminders, PetContextLoader contexts,
            HealthDispatchPort dispatcher, HealthReminderAnalytics analytics, HealthReminderLock lock) {
        this.pets = pets;
        this.reminders = reminders;
        this.contexts = contexts;
        this.dispatcher = dispatcher;
        this.analytics = analytics;
        this.lock = lock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PetDayResult process(long petId, LocalDate day, Occupancy occ) {
        lock.lockPet(petId);
        PetProfile pet = pets.findById(petId).orElse(null);
        if (pet == null) {
            return PetDayResult.empty();
        }
        List<HealthReminder> pending = reminders.findByPetProfileIdAndStatus(petId, ReminderStatus.PENDING);
        List<HealthReminder> today = pending.stream()
                .filter(r -> r.getOrigin() != ReminderOrigin.LAUNCH_QUEUE && r.getSendDate().equals(day)).toList();
        if (today.isEmpty()) {
            return PetDayResult.empty();
        }
        Set<LocalDate> futurePending = pending.stream()
                .filter(r -> r.getOrigin() != ReminderOrigin.LAUNCH_QUEUE && r.getSendDate().isAfter(day))
                .map(HealthReminder::getSendDate).collect(Collectors.toSet());
        PetReminderContext ctx = contexts.load(pet);
        HealthReminderPlanner.PetDayDecision decision = HealthReminderPlanner.decide(
                new HealthReminderPlanner.PetDayInput(day, ctx.pet(), PregnancyFlag.isActiveOn(pet, day), today, ctx,
                        occ.scheduledPushToday(), occ.recallToday(), futurePending, occ.futureScheduledPushDays(),
                        occ.futureRecallDays()));
        Map<CancelReason, Integer> cancelled = new EnumMap<>(CancelReason.class);
        for (HealthReminderPlanner.Cancel c : decision.cancels()) {
            c.row().cancel(c.reason(), c.displacedBy());
            reminders.save(c.row());
            cancelled.merge(c.reason(), 1, Integer::sum);
            AfterCommit.run(() -> analytics.cancelled(c.row(), c.reason(), c.displacedBy(), c.byRecordId()));
        }
        for (HealthReminderPlanner.Move m : decision.moves()) {
            if (m.countsAsDeferral()) {
                m.row().deferTo(m.to());
            } else {
                m.row().moveSendDate(m.to());
            }
            reminders.save(m.row());
        }
        reminders.flush();
        int sent = 0;
        for (SendGroup g : decision.sendGroups()) {
            if (dispatcher.dispatch(g).sent()) {
                sent++;
            }
        }
        return new PetDayResult(sent, cancelled);
    }
}
