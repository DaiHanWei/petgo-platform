package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.rules.LaunchQueueRules;
import com.tailtopia.healthreminder.rules.PetReminderContext;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上线排队的生成与出队（V1.5.0 Story 2.2 · AC5 / AC6，PRD FR-164 Launch queue，架构增量 §4.7）。出队由 2.3 日跑在该用户
 * 常规提醒处理<b>之后</b>调用 {@link #pickForUser}，投递成功后调 {@link #markQueueSent}。
 *
 * <p>排队行永不 {@code expired_defer}、不受 3 天顺延上限；未出队的行每天把 send_date 改为次日。
 */
@Service
public class LaunchQueueService {

    /** 两条排队推送的最小间隔（天）。 */
    static final int QUEUE_GAP_DAYS = 3;
    /** 起始日分散：launch + (user_id mod 7)。 */
    static final int SPREAD_DAYS = 7;

    private final UserLaunchDao userLaunch;
    private final PetProfileRepository pets;
    private final HealthReminderRepository reminders;
    private final PetContextLoader contexts;
    private final HealthReminderAnalytics analytics;
    private final HealthReminderLock lock;

    public LaunchQueueService(UserLaunchDao userLaunch, PetProfileRepository pets, HealthReminderRepository reminders,
            PetContextLoader contexts, HealthReminderAnalytics analytics, HealthReminderLock lock) {
        this.userLaunch = userLaunch;
        this.pets = pets;
        this.reminders = reminders;
        this.contexts = contexts;
        this.analytics = analytics;
        this.lock = lock;
    }

    /**
     * 个人上线：建 {@code health_reminder_user_launch} 行（上线日 = {@code day}）并把该用户全部宠物的合格建议排进队。
     * 已有上线行 → 什么都不做。@return 排进队的条数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int enqueueUser(long userId, LocalDate day) {
        LocalDate queueStart = day.plusDays(Math.floorMod(userId, SPREAD_DAYS));
        if (!userLaunch.insertIfAbsent(userId, day, queueStart)) {
            return 0;
        }
        List<PetProfile> owned = pets.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId);
        List<PetReminderContext> ctxs = owned.stream().map(contexts::load).toList();
        Map<Long, PetProfile> byId = owned.stream().collect(Collectors.toMap(PetProfile::getId, Function.identity()));
        int position = 0;
        for (LaunchQueueRules.QueueItem item : LaunchQueueRules.items(ctxs, day)) {
            lock.lockPet(item.petId());
            HealthReminder r = HealthReminder.pending(item.petId(), byId.get(item.petId()).getOwnerId(), null,
                    item.kind(), PushPoint.NONE, ReminderOrigin.LAUNCH_QUEUE, null, day);
            r.moveSendDate(queueStart);
            r.setQueuePosition(++position);
            reminders.save(r);
        }
        return position;
    }

    /**
     * 今天要不要从该用户的队列里发一条（PRD FR-164「Real reminders come first」「Checked again」）。
     *
     * @param userHasAnyPushToday 本次日跑已给该用户发了健康通知，或任一宠物今天有生日 / 第一个生日 / 纪念日推送，或今天是召回日
     * @return 选中的一行（仍是 PENDING，由 2.3 投递）；否则空
     */
    @Transactional
    public Optional<HealthReminder> pickForUser(long userId, LocalDate day, boolean userHasAnyPushToday,
            boolean queuePaused) {
        Map<Long, PetProfile> owned = pets.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId).stream()
                .collect(Collectors.toMap(PetProfile::getId, Function.identity()));
        // 与重建 / 删宠清理同一把宠物锁（按 id 升序取，避免互相等待）。
        owned.keySet().stream().sorted().forEach(lock::lockPet);
        List<HealthReminder> queue = pendingQueue(owned.keySet());
        if (queue.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserLaunchDao.UserLaunch> launch = userLaunch.find(userId);
        boolean blocked = queuePaused || launch.isEmpty() || day.isBefore(launch.get().queueStartDate())
                || recentlySent(launch.get(), day) || userHasAnyPushToday;
        HealthReminder picked = null;
        if (!blocked) {
            Map<Long, PetReminderContext> ctxCache = new java.util.HashMap<>();
            for (HealthReminder row : queue) {
                Optional<PetProfile> pet = Optional.ofNullable(owned.get(row.getPetProfileId()));
                Optional<CancelReason> reason = pet.isEmpty() ? Optional.of(CancelReason.QUEUE_RECHECK)
                        : LaunchQueueRules.recheck(row.getReminderKind(),
                                ctxCache.computeIfAbsent(row.getPetProfileId(), id -> contexts.load(pet.get())), day);
                if (reason.isPresent()) {
                    row.cancel(reason.get(), null);
                    reminders.save(row);
                    CancelReason r = reason.get();
                    AfterCommit.run(() -> analytics.cancelled(row, r, null, null));
                    continue;
                }
                picked = row;
                break;
            }
        }
        // 没出队的行把计划发送日挪到次日（排队永不过期、不计顺延）。
        for (HealthReminder row : queue) {
            if (row != picked && row.getStatus() == ReminderStatus.PENDING && !row.getSendDate().isAfter(day)) {
                row.moveSendDate(day.plusDays(1));
                reminders.save(row);
            }
        }
        if (picked != null && picked.getSendDate().isAfter(day)) {
            picked.moveSendDate(day);
            reminders.save(picked);
        }
        return Optional.ofNullable(picked);
    }

    /** 排队推送投递后记下发送日（下一条至少隔 3 天）。 */
    @Transactional
    public void markQueueSent(long userId, LocalDate day) {
        userLaunch.markQueueSent(userId, day);
    }

    private List<HealthReminder> pendingQueue(java.util.Collection<Long> petIds) {
        List<HealthReminder> out = new ArrayList<>();
        for (Long petId : petIds) {
            for (HealthReminder r : reminders.findByPetProfileIdAndStatus(petId, ReminderStatus.PENDING)) {
                if (r.getOrigin() == ReminderOrigin.LAUNCH_QUEUE) {
                    out.add(r);
                }
            }
        }
        out.sort(Comparator.comparing(HealthReminder::getQueuePosition, Comparator.nullsLast(Integer::compareTo)));
        return out;
    }

    static boolean recentlySent(UserLaunchDao.UserLaunch launch, LocalDate day) {
        LocalDate last = launch.lastQueueSentDate();
        return last != null && last.isAfter(day.minusDays(QUEUE_GAP_DAYS));
    }
}
