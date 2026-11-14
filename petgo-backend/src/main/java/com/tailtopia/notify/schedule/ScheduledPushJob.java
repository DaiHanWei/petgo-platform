package com.tailtopia.notify.schedule;

import com.tailtopia.profile.dto.PetProfileSnapshot;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时类系统推送日扫调度器（Story 6.7 · 决策 F5；V1.5.0 onboarding-recall Story 5.2 · OR-16 拆成两个日扫）。
 *
 * <ul>
 *   <li>{@link #runBirthdayScan}：11:00 WIB（{@code 0 0 4 * * *} UTC），只产出 {@code PET_BIRTHDAY} + 第一个生日
 *       {@code MILESTONE_NODE}（规则 / 文案 / 去重 / 落点不变，FR-186 只改时间）；</li>
 *   <li>{@link #runAnniversaryScan}：19:00 WIB（{@code 0 0 12 * * *} UTC），只产出 {@code COMPANION_ANNIVERSARY}
 *       （FR-182）；受 {@code petgo.scheduled-push.anniversary-enabled}（env {@code ANNIVERSARY_PUSH_ENABLED}）控制。</li>
 * </ul>
 * 日期一律 WIB（{@code today = LocalDate.now(WIB)}）。Spring 原生 {@code @Scheduled}——<b>禁 Quartz / Kafka / 任何调度或消息中间件</b>。
 * 经 profile 只读端口取档案快照 → {@link ScheduledPushPlanner} 纯逻辑算当日应推集 → {@link ScheduledPushDispatcher}
 * 逐条 {@code @Async} 投递；多实例时去重表唯一约束天然防重复。旧键 {@code petgo.scheduled-push.cron} 废弃。
 */
@Component
public class ScheduledPushJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPushJob.class);

    private final PetProfileQueryService petProfileQuery;
    private final ScheduledPushPlanner planner;
    private final ScheduledPushDispatcher dispatcher;
    private final boolean anniversaryEnabled;
    private final Clock clock;

    @Autowired
    public ScheduledPushJob(PetProfileQueryService petProfileQuery, ScheduledPushPlanner planner,
            ScheduledPushDispatcher dispatcher,
            @Value("${petgo.scheduled-push.anniversary-enabled:true}") boolean anniversaryEnabled) {
        this(petProfileQuery, planner, dispatcher, anniversaryEnabled, Clock.systemUTC());
    }

    ScheduledPushJob(PetProfileQueryService petProfileQuery, ScheduledPushPlanner planner,
            ScheduledPushDispatcher dispatcher, boolean anniversaryEnabled, Clock clock) {
        this.petProfileQuery = petProfileQuery;
        this.planner = planner;
        this.dispatcher = dispatcher;
        this.anniversaryEnabled = anniversaryEnabled;
        this.clock = clock;
    }

    /** 生日 + 第一个生日（11:00 WIB）。 */
    @Scheduled(cron = "${petgo.scheduled-push.birthday-cron:0 0 4 * * *}", zone = "UTC")
    public void runBirthdayScan() {
        LocalDate today = today();
        List<PetProfileSnapshot> profiles = petProfileQuery.allSnapshots();
        // 去重权威为 DB 唯一约束（dispatcher 内 existsBy + 唯一约束）；此处传空集，仅按日期算候选。
        List<PlannedPush> planned = planner.planBirthdays(today, profiles, Set.of());
        log.info("scheduled push birthday scan: profiles={} planned={}", profiles.size(), planned.size());
        planned.forEach(dispatcher::dispatch);
    }

    /** 陪伴纪念日（19:00 WIB）；开关关闭 → no-op。 */
    @Scheduled(cron = "${petgo.scheduled-push.anniversary-cron:0 0 12 * * *}", zone = "UTC")
    public void runAnniversaryScan() {
        if (!anniversaryEnabled) {
            log.info("scheduled push anniversary scan skipped: disabled");
            return;
        }
        LocalDate today = today();
        List<PetProfileSnapshot> profiles = petProfileQuery.allSnapshots();
        List<PlannedPush> planned = planner.planAnniversaries(today, profiles, Set.of());
        log.info("scheduled push anniversary scan: profiles={} planned={}", profiles.size(), planned.size());
        planned.forEach(dispatcher::dispatch);
    }

    /** 今天（WIB）。 */
    LocalDate today() {
        return LocalDate.now(clock.withZone(ScheduleWindow.WIB));
    }
}
