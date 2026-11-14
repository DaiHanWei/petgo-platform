package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.service.HealthReminderSettingsDao;
import com.tailtopia.healthreminder.service.LaunchService;
import com.tailtopia.healthreminder.service.PregnancyResumeHandler;
import com.tailtopia.profile.service.PregnancyAutoClearService;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 健康提醒日跑（V1.5.0 Story 2.3 · AC1，架构增量 §5.1，PRD FR-166 Send time）：每天 10:00 WIB（cron 03:00 UTC）。
 * 🔴 日期一律 WIB（{@code ScheduleWindow.WIB}），不照抄 {@code ScheduledPushJob} 的 UTC 取日期。
 *
 * <p>顺序：读开关 → ① 自动清除到期怀孕标记并同步走恢复处理 → ② 上线首跑 / 个人上线 → ③ 过期作废 → ④ 当天待发、版本门槛
 * → ⑤ 占用 → ⑥⑦ 每宠物决策与投递（每宠物一个事务，失败只 warn）→ ⑧ 每用户出队。同日重跑安全：只处理仍 PENDING 的行。
 * 🔒 日志只记计数。
 */
@Component
public class HealthReminderJob {

    private static final Logger log = LoggerFactory.getLogger(HealthReminderJob.class);

    /** 一次日跑的计数（QA 端点原样返回）。 */
    public record RunResult(LocalDate day, boolean enabled, int pets, int groupsSent, int queueSent,
            Map<String, Integer> cancelledByReason) {
    }

    private final HealthReminderSettingsDao settings;
    private final PregnancyAutoClearService pregnancyAutoClear;
    private final PregnancyResumeHandler resumeHandler;
    private final LaunchService launch;
    private final DailyRunStore store;
    private final OccupancyCalendar occupancy;
    private final PetDayProcessor processor;
    private final QueueDayProcessor queueProcessor;

    public HealthReminderJob(HealthReminderSettingsDao settings, PregnancyAutoClearService pregnancyAutoClear,
            PregnancyResumeHandler resumeHandler, LaunchService launch, DailyRunStore store,
            OccupancyCalendar occupancy, PetDayProcessor processor, QueueDayProcessor queueProcessor) {
        this.settings = settings;
        this.pregnancyAutoClear = pregnancyAutoClear;
        this.resumeHandler = resumeHandler;
        this.launch = launch;
        this.store = store;
        this.occupancy = occupancy;
        this.processor = processor;
        this.queueProcessor = queueProcessor;
    }

    @Scheduled(cron = "${petgo.health-reminder.cron:0 0 3 * * *}", zone = "UTC")
    public void runDaily() {
        runFor(LocalDate.now(ScheduleWindow.WIB));
    }

    public RunResult runFor(LocalDate day) {
        // ① 自动清除到期怀孕标记：属档案规则，不受上线开关影响。当天 00:00 WIB 视为清除，同步走恢复表（R = 当天）；
        //    事件带 autoCleared=true，异步监听不再重复处理。
        for (Long petId : pregnancyAutoClear.clearExpiredPets(day)) {
            try {
                resumeHandler.onPregnancyCleared(petId, day.atStartOfDay(ScheduleWindow.WIB).toInstant());
            } catch (RuntimeException ex) {
                log.warn("health reminder resume failed petId={} error={}", petId, ex.getClass().getSimpleName());
            }
        }
        HealthReminderSettingsDao.Settings s = settings.read();
        if (!s.enabled()) {
            log.info("health reminder run day={} skipped: disabled", day);
            return new RunResult(day, false, 0, 0, 0, Map.of());
        }
        Map<String, Integer> cancelled = new HashMap<>();
        // ② 上线首跑 / 个人上线（当天的点也排上）。
        launch.onRun(day);
        // ③ 过期作废（排队行挪到今天）。
        merge(cancelled, store.expireMissed(day));
        // ④ 当天待发 + 版本门槛。
        DailyRunStore.DuePets due = store.duePets(day);
        merge(cancelled, due.oldAppCancelled());
        // ⑤ 占用（一次批量）：当天待发的宠物 + 有排队用户的全部宠物。
        List<Long> queueUsers = store.usersWithQueue();
        Map<Long, Long> ownerByPet = new HashMap<>(due.ownerByPet());
        ownerByPet.putAll(store.petsOfOwners(queueUsers));
        OccupancyCalendar.Calendar cal = occupancy.load(ownerByPet, day);
        // ⑥⑦ 每宠物决策与投递。
        int groups = 0;
        Set<Long> usersWithPushToday = new HashSet<>(store.usersNotifiedOn(day));
        for (Long petId : due.petIds()) {
            long owner = due.ownerByPet().get(petId);
            try {
                PetDayProcessor.PetDayResult r = processor.process(petId, day, cal.occupancyFor(petId, owner));
                groups += r.groupsSent();
                if (r.groupsSent() > 0) {
                    usersWithPushToday.add(owner);
                }
                r.cancelled().forEach((reason, n) -> cancelled.merge(reason.wire(), n, Integer::sum));
            } catch (RuntimeException ex) {
                log.warn("health reminder pet failed petId={} error={}", petId, ex.getClass().getSimpleName());
            }
        }
        // ⑧ 每用户出队：在该用户常规提醒之后；当天已有任何推送（健康通知 / 生日 / 纪念日 / 召回日）则让路。
        int queueSent = 0;
        Set<Long> busyOwners = cal.busyOwnersToday();
        for (Long userId : queueUsers) {
            try {
                boolean busy = usersWithPushToday.contains(userId) || busyOwners.contains(userId);
                if (queueProcessor.process(userId, day, busy, s.queuePaused())) {
                    queueSent++;
                }
            } catch (RuntimeException ex) {
                log.warn("health reminder queue failed userId={} error={}", userId, ex.getClass().getSimpleName());
            }
        }
        log.info("health reminder run day={} pets={} groupsSent={} queueSent={} cancelled={}", day,
                due.petIds().size(), groups, queueSent, cancelled);
        return new RunResult(day, true, due.petIds().size(), groups, queueSent, cancelled);
    }

    private static void merge(Map<String, Integer> into, Map<CancelReason, Integer> add) {
        add.forEach((k, v) -> into.merge(k.wire(), v, Integer::sum));
    }
}
