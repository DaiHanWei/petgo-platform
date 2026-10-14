package com.tailtopia.notify.ops.service;

import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运营通知定时扫描（V1.3.4 Story 6.2 · AC5 · D-20）：{@code @Scheduled} + DB 状态守卫，禁 MQ / 延迟队列。
 *
 * <ol>
 *   <li><b>超时扫</b>（先做）：{@code SENDING} 且 {@code last_progress_at} 超过 15 分钟未刷新 →
 *       {@code PARTIAL_FAILED}，<b>不自动重发</b>（已写入人数无法与「推到第几个」对齐，重发必然重复推送）。</li>
 *   <li><b>到期扫</b>：{@code PENDING} 且（立即 或 已到点）的活动，逐个交给 {@code opsNotificationExecutor}
 *       异步发送。重复提交无害（抢占防重）；{@code CANCELED} 不在扫描集，天然不发。</li>
 * </ol>
 * 扫描本身<b>不在调度线程里发送</b>（调度池只有 4 线程，逐人推送可能持续数分钟）。
 */
@Component
public class OpsNotificationScanner {

    private static final Logger log = LoggerFactory.getLogger(OpsNotificationScanner.class);

    private final OpsNotificationCampaignRepository repo;
    private final OpsNotificationDispatcher dispatcher;
    private final Clock clock;
    private final OpsNotifyParams params;

    @Autowired
    public OpsNotificationScanner(OpsNotificationCampaignRepository repo, OpsNotificationDispatcher dispatcher) {
        this(repo, dispatcher, Clock.systemUTC(), OpsNotifyParams.DEFAULTS);
    }

    /** 测试用：固定时钟。 */
    public OpsNotificationScanner(OpsNotificationCampaignRepository repo, OpsNotificationDispatcher dispatcher,
            Clock clock, OpsNotifyParams params) {
        this.repo = repo;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.params = params;
    }

    @Scheduled(fixedDelayString = "${petgo.ops-notify.scan-ms:60000}",
            initialDelayString = "${petgo.ops-notify.scan-initial-delay-ms:60000}")
    public void scan() {
        Instant now = clock.instant();
        markStalled(now);
        for (Long id : repo.findDueIds(now, params.scanLimit())) {
            dispatcher.dispatchAsync(id);
        }
    }

    private void markStalled(Instant now) {
        Instant threshold = now.minus(params.stallTimeout());
        List<Long> marked = new ArrayList<>();
        for (Long id : repo.findStalledIds(threshold)) {
            if (repo.markStalled(id, threshold, now) > 0) {
                marked.add(id);
            }
        }
        if (!marked.isEmpty()) {
            log.warn("运营通知发送超时无进展，已置 PARTIAL_FAILED（不自动重发） campaignIds={}", marked);
        }
    }
}
