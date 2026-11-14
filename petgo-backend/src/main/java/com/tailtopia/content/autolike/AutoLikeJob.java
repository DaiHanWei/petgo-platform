package com.tailtopia.content.autolike;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动点赞定时计划：每天 10:30 与 16:30（WIB）各排一轮（V1.5.0 Story 2.2，FR-212）。真正点赞由派发器做（Story 2.3）。
 *
 * <p>🔴 {@code zone} 必须显式写 Asia/Jakarta：容器默认 UTC，不写会晚 7 小时。
 * 与 {@code AutoCommentJob} 无任何共享状态（A-AC3）；多实例防重靠 {@link AutoLikePlanner} 的库锁，不靠进程内标志。
 */
@Component
public class AutoLikeJob {

    private static final Logger log = LoggerFactory.getLogger(AutoLikeJob.class);

    private final AutoLikePlanner planner;
    private final AutoLikeProperties props;
    private final Clock clock;

    /** 🔴 双构造器必须标 {@code @Autowired}，否则 Spring 选不出构造器、启动即崩。 */
    @Autowired
    public AutoLikeJob(AutoLikePlanner planner, AutoLikeProperties props) {
        this(planner, props, Clock.systemUTC());
    }

    AutoLikeJob(AutoLikePlanner planner, AutoLikeProperties props, Clock clock) {
        this.planner = planner;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${petgo.auto-like.morning-cron:0 30 10 * * *}", zone = "Asia/Jakarta")
    public void morning() {
        plan(AutoLikeSlot.MORNING);
    }

    @Scheduled(cron = "${petgo.auto-like.evening-cron:0 30 16 * * *}", zone = "Asia/Jakarta")
    public void evening() {
        plan(AutoLikeSlot.EVENING);
    }

    void plan(AutoLikeSlot slot) {
        if (!props.isEnabled()) {
            log.info("auto like disabled, skip {} run", slot);
            return;
        }
        try {
            planner.plan(slot, clock.instant());
        } catch (RuntimeException e) {
            log.warn("auto like {} plan failed: {}", slot, e.getClass().getSimpleName());
        }
    }
}
