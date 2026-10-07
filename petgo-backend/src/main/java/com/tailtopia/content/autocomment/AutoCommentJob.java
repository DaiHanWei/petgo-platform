package com.tailtopia.content.autocomment;

import com.tailtopia.shared.ai.PetCommentGenerator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动评论定时任务：每天 10:30 与 16:30（WIB）各跑一轮（2026-09-29 由 09:30 / 20:00 调整为 10:30 / 20:30；2026-10-07 晚场改 16:30）。
 *
 * <p>🔴 {@code zone} 必须显式写 Asia/Jakarta：容器默认 UTC，不写会晚 7 小时。
 * 调度开关由 {@code AsyncConfig} 的 {@code @EnableScheduling} 统一开启；禁引 Quartz 等调度中间件。
 * 调度线程池为 4（application.yml），一轮串行跑几分钟不会饿死其他扫描器。
 *
 * <p>两道闸：部署层开关 {@code petgo.auto-comment.enabled}；AI 是 stub 时整轮跳过（固定文案绝不发到真实帖子下）。
 */
@Component
public class AutoCommentJob {

    private static final Logger log = LoggerFactory.getLogger(AutoCommentJob.class);

    private final AutoCommentService service;
    private final AutoCommentProperties props;
    private final PetCommentGenerator generator;
    /** 早晚两场不会重叠，但 stag 把 cron 调密时可能撞上上一轮：同一时刻只跑一轮。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AutoCommentJob(AutoCommentService service, AutoCommentProperties props, PetCommentGenerator generator) {
        this.service = service;
        this.props = props;
        this.generator = generator;
    }

    @Scheduled(cron = "${petgo.auto-comment.morning-cron:0 30 10 * * *}", zone = "Asia/Jakarta")
    public void morning() {
        run("morning");
    }

    @Scheduled(cron = "${petgo.auto-comment.evening-cron:0 30 16 * * *}", zone = "Asia/Jakarta")
    public void evening() {
        run("evening");
    }

    void run(String slot) {
        if (!props.isEnabled()) {
            log.info("auto comment disabled, skip {} run", slot);
            return;
        }
        if (!generator.live()) {
            log.warn("auto comment skipped {} run: AI generator is stub (set GEMINI_MODE=live)", slot);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("auto comment {} run skipped: previous run still in progress", slot);
            return;
        }
        try {
            service.runOnce();
        } finally {
            running.set(false);
        }
    }
}
