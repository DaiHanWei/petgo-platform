package com.tailtopia.social.service;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后给老用户补关注自动关注目标（V1.4.1，「老用户也默认全部关注」TailUs）。
 *
 * <p>每次启动都跑一遍，幂等：已关注的跳过；用户之后主动取关会记 optout（{@code FollowService.unfollow}），
 * 不会被下一次启动关注回来。目标未配置 = 什么都不做（测试 / 未开通环境）。
 * 开关 {@code petgo.social.auto-follow-backfill-on-startup}（默认开）。失败只记日志，不挡启动。
 */
@Component
public class AutoFollowBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoFollowBackfillRunner.class);

    private final OfficialAutoFollowService service;
    private final AutoFollowTargets targets;
    private final boolean enabled;

    public AutoFollowBackfillRunner(OfficialAutoFollowService service, AutoFollowTargets targets,
            @Value("${petgo.social.auto-follow-backfill-on-startup:true}") boolean enabled) {
        this.service = service;
        this.targets = targets;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || targets.ids().isEmpty()) {
            return;
        }
        try {
            Map<Long, Integer> added = service.backfill();
            log.info("自动关注补关注完成 targets={} added={}", targets.ids(), added);
        } catch (RuntimeException e) {
            log.warn("自动关注补关注失败（不挡启动，下次启动重试） targets={}", targets.ids(), e);
        }
    }
}
