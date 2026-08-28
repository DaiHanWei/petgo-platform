package com.tailtopia.notify.lifecycle;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import com.tailtopia.profile.service.PetProfileQueryService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final PetProfileQueryService petProfiles;
    private final LifecyclePushPlanner planner;
    private final LifecyclePushDispatcher dispatcher;
    private final LifecyclePushProperties props;

    public LifecyclePushJob(AccountQueryService accounts, PetProfileQueryService petProfiles,
            LifecyclePushPlanner planner, LifecyclePushDispatcher dispatcher,
            LifecyclePushProperties props) {
        this.accounts = accounts;
        this.petProfiles = petProfiles;
        this.planner = planner;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    /** 每日 19:00 WIB（12:00 UTC）扫描；cron 与开关可经配置覆盖。 */
    @Scheduled(cron = "${petgo.lifecycle-push.cron:0 0 12 * * *}", zone = "UTC")
    public void runDailyScan() {
        if (!props.isEnabled()) {
            log.info("lifecycle push disabled, skip daily scan");
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<UserLifecycleSnapshot> users = accounts.lifecycleSnapshots();
        Map<Long, String> petNames = petNameByOwner();
        List<LifecyclePlannedPush> planned =
                planner.plan(today, users, petNames, props.getWinbackAfterDays());

        int cap = Math.max(0, props.getDailyCap());
        int sent = 0;
        for (LifecyclePlannedPush push : planned) {
            if (sent >= cap) {
                break; // planned 已按 D1→D3→D7→召回 排序，截断先砍掉最不紧迫的召回。
            }
            dispatcher.dispatch(push); // @Async 逐条；去重 + 投递在内。
            sent++;
        }
        // 手册每日 SOP「看召回漏斗」的第一行数据。planned > sent 说明当天被上限压住了，
        // 剩下的明天补 —— 不记 userId / 宠物名（日志禁 PII）。
        log.info("lifecycle push daily scan: users={} planned={} dispatched={} cap={} deferred={}",
                users.size(), planned.size(), sent, cap, Math.max(0, planned.size() - sent));
    }

    /**
     * owner → 宠物名映射（<b>键存在即已建档</b>）。
     *
     * <p>V1 单宠物，同一 owner 理论上只有一条档案；真出现多条时保留先建的那只
     * （{@code merge} 取旧值），避免推送里的名字每天换一只。
     */
    private Map<Long, String> petNameByOwner() {
        Map<Long, String> map = new HashMap<>();
        for (PetProfileSnapshot p : petProfiles.allSnapshots()) {
            map.merge(p.ownerId(), p.name() == null ? "" : p.name(), (old, fresh) -> old);
        }
        return map;
    }
}
