package com.tailtopia.notify.lifecycle;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.port.LifecycleContextPort;
import com.tailtopia.notify.port.LifecycleContextPort.LifecycleContext;
import com.tailtopia.notify.repository.LifecyclePushMarkRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 生命周期推送日扫调度器（留存运营作战手册 · 抓手 1）。
 *
 * <p>Spring 原生 {@code @Scheduled} 每日固定 UTC 时点扫一次 —— <b>禁 Quartz / Kafka / 任何调度或消息中间件</b>
 * （沿用 Story 6.7 {@code ScheduledPushJob} 的形状）。取用户生命周期快照 + 宠物名映射 →
 * {@link LifecyclePushPlanner} 纯逻辑算出当日应推集 → {@link LifecyclePushDispatcher} 逐条 {@code @Async} 投递。
 *
 * <p>单机单实例日扫足够（≤500 DAU）；多实例时去重表唯一约束天然防重复（无需分布式锁中间件）。
 */
@Component
@EnableConfigurationProperties(LifecyclePushProperties.class)
public class LifecyclePushJob {

    private static final Logger log = LoggerFactory.getLogger(LifecyclePushJob.class);

    private final AccountQueryService accounts;
    private final LifecycleContextPort context;
    private final LifecyclePushPlanner planner;
    private final LifecyclePushDispatcher dispatcher;
    private final LifecyclePushProperties props;
    private final LifecyclePushMarkRepository marks;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LifecyclePushJob(AccountQueryService accounts, LifecycleContextPort context,
            LifecyclePushPlanner planner, LifecyclePushDispatcher dispatcher,
            LifecyclePushProperties props,
            LifecyclePushMarkRepository marks) {
        this(accounts, context, planner, dispatcher, props, marks, Clock.systemUTC());
    }

    LifecyclePushJob(AccountQueryService accounts, LifecycleContextPort context, LifecyclePushPlanner planner,
            LifecyclePushDispatcher dispatcher, LifecyclePushProperties props, LifecyclePushMarkRepository marks,
            Clock clock) {
        this.accounts = accounts;
        this.context = context;
        this.planner = planner;
        this.dispatcher = dispatcher;
        this.props = props;
        this.marks = marks;
        this.clock = clock;
    }

    /** 每日 19:00 WIB（12:00 UTC）扫描；cron 与开关可经配置覆盖。 */
    @Scheduled(cron = "${petgo.lifecycle-push.cron:0 0 12 * * *}", zone = "UTC")
    public void runDailyScan() {
        if (!props.isEnabled()) {
            log.info("lifecycle push disabled, skip daily scan");
            return;
        }
        // V1.5.0 onboarding-recall Story 6.1 · AC1：日期一律 WIB；候选按 D1 / D3 / D7 窗口在 SQL 里收窄（OR-21）。
        LocalDate today = LocalDate.now(clock.withZone(ScheduleWindow.WIB));
        List<UserLifecycleSnapshot> users = accounts.lifecycleSnapshots(today);
        LifecycleContext ctx = context.contextOf(users.stream().map(UserLifecycleSnapshot::userId).toList(), today);
        List<LifecyclePlannedPush> planned = planner.plan(today, users, ctx, planOptions());

        int cap = Math.max(0, props.getDailyCap());
        // 🔴 先剔除已推过的计划项再套上限：planner 不知道去重表（D1 两天窗口、召回每天都重新计划），
        //    不剔除的话前一天已推的条目会占满今天的 cap，第 201 人起「明天补」永远补不到。
        List<LifecyclePlannedPush> due = planned.stream()
                .filter(push -> !marks.existsByUserIdAndPushKindAndNodeKey(
                        push.userId(), push.type().name(), push.nodeKey()))
                .toList();
        int sent = 0;
        int skippedActive = 0;
        for (LifecyclePlannedPush push : due) {
            if (push.variant() == LifecycleVariant.SKIPPED_ACTIVE) {
                dispatcher.dispatch(push); // 只写作废 mark，不占当日推送上限
                skippedActive++;
                continue;
            }
            if (sent >= cap) {
                break; // planned 已按 D1→D3→D7→召回 排序，截断先砍掉最不紧迫的召回。
            }
            dispatcher.dispatch(push); // @Async 逐条；去重 + 投递在内。
            sent++;
        }
        // 手册每日 SOP「看召回漏斗」的第一行数据 —— 不记 userId / 宠物名（日志禁 PII）。
        log.info("lifecycle push daily scan: users={} planned={} due={} dispatched={} skippedActive={} cap={}",
                users.size(), planned.size(), due.size(), sent, skippedActive, cap);
    }

    /** 召回节点本 story 先关（6-2 接 {@code winback-enabled} 配置）。 */
    LifecyclePlanOptions planOptions() {
        return new LifecyclePlanOptions(false, props.getWinbackAfterDays());
    }
}
