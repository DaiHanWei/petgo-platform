package com.petgo.notify.schedule;

import com.petgo.profile.dto.PetProfileSnapshot;
import com.petgo.profile.service.PetProfileQueryService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时类系统推送日扫调度器（Story 6.7 · 决策 F5）。
 *
 * <p>Spring 原生 {@code @Scheduled} 每日固定 UTC 时点扫一次——<b>禁 Quartz / Kafka / 任何调度或消息中间件</b>。
 * 经 profile 只读端口取档案快照 → {@link ScheduledPushPlanner} 纯逻辑算出当日应推集 →
 * {@link ScheduledPushDispatcher} 逐条 {@code @Async} 投递。单机单实例日扫足够（≤500 DAU）；
 * 多实例时去重表唯一约束天然防重复（无需分布式锁中间件）。
 */
@Component
public class ScheduledPushJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPushJob.class);

    private final PetProfileQueryService petProfileQuery;
    private final ScheduledPushPlanner planner;
    private final ScheduledPushDispatcher dispatcher;

    public ScheduledPushJob(PetProfileQueryService petProfileQuery, ScheduledPushPlanner planner,
            ScheduledPushDispatcher dispatcher) {
        this.petProfileQuery = petProfileQuery;
        this.planner = planner;
        this.dispatcher = dispatcher;
    }

    /** 每日 01:00 UTC 扫描（cron 可经配置覆盖；前 1 天/当天文案以此 UTC 基准）。 */
    @Scheduled(cron = "${petgo.scheduled-push.cron:0 0 1 * * *}", zone = "UTC")
    public void runDailyScan() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<PetProfileSnapshot> profiles = petProfileQuery.allSnapshots();
        // 去重权威为 DB 唯一约束（dispatcher 内 existsBy + 唯一约束）；此处传空集，仅按日期算候选。
        List<PlannedPush> planned = planner.plan(today, profiles, Set.of());
        log.info("scheduled push daily scan: profiles={} planned={}", profiles.size(), planned.size());
        for (PlannedPush push : planned) {
            dispatcher.dispatch(push); // @Async 逐条；去重 + 投递在内。
        }
    }
}
