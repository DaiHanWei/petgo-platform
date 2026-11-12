package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.RollupService.Outcome;
import com.tailtopia.analytics.rollup.RollupService.RunResult;
import com.tailtopia.analytics.store.EventTimeCorrector;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日汇总（analytics-pipeline Story 4.1，架构「汇总」）：WIB 03:00（晚于 02:00 的 {@code ErasureJob}），
 * {@code asOf} = WIB 昨天，按各指标族的 {@code agg_progress} 续算，每次重算最近 3 天（D-6）。
 *
 * <p>防重入：{@link RollupService} 的 JVM 内 {@code ReentrantLock.tryLock()} + 每个单元事务内
 * {@code pg_try_advisory_xact_lock(NAMESPACE, ROLLUP)}，拿不到则本轮跳过并记 INFO。
 * 埋点管道停用（库连不上等）→ 直接跳过并记日志，不抛。测试设 {@code analytics.rollup.cron=-} 关闭，直接调 {@link #run()}。
 */
@Component
public class RollupJob {

    private static final Logger log = LoggerFactory.getLogger(RollupJob.class);

    private final RollupService service;
    private final Clock clock;

    @Autowired
    public RollupJob(RollupService service) {
        this(service, Clock.systemUTC());
    }

    RollupJob(RollupService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${analytics.rollup.cron:0 0 3 * * *}", zone = "Asia/Jakarta")
    public void scheduled() {
        run();
    }

    /** 跑一轮（asOf = WIB 昨天）。绝不抛。 */
    public RunResult run() {
        LocalDate asOf = asOf(clock);
        try {
            return service.runScheduled(asOf);
        } catch (RuntimeException e) {
            log.warn("analytics rollup job crashed: {}", RollupService.describe(e));
            return RunResult.of(Outcome.FAILED);
        }
    }

    /** 本次运行的基准日：当前时刻的 WIB 昨天。 */
    static LocalDate asOf(Clock clock) {
        return EventTimeCorrector.wibDate(clock.instant()).minusDays(1);
    }
}
