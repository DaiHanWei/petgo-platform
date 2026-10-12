package com.tailtopia.notify.ops.service;

import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignReadyEvent;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.notify.service.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 运营通知发送（V1.3.4 Story 6.2 · AC3 · D-19 / D-20）。
 *
 * <h2>状态机（全部条件更新，多实例安全）</h2>
 * {@code PENDING →(claimForSending) SENDING →(finish) DONE | PARTIAL_FAILED}；超时扫描可把
 * {@code SENDING} 改成 {@code PARTIAL_FAILED}——此后本类任何一次进度写返回 0 行即<b>立即停止</b>。
 *
 * <h2>流程</h2>
 * 抢占 → 按<b>实际发送时刻</b>解析人群 → 每 500 人一批：写通知中心（整批一事务，失败按 1s/2s/4s 重试 3 次，
 * 仍失败整批计 failed 且跳过推送）→ 逐人同步推送（失败的人按 1s/2s/4s 重推 3 轮，成功的人不重推）→
 * 批间等 1 秒 → 收尾。
 *
 * <p>本身不加事务；每一步各自短事务（仓储方法自带 / {@code writeOpsAnnouncements} REQUIRES_NEW）。
 * 已写入通知中心的行任何情况下都不回滚、不删除（PRD AC6）。
 *
 * <p>🔒 日志只记活动 id、批次序号、本批写入数、本批失败数、最终状态；不记收件人 id、标题、正文、token。
 */
@Service
public class OpsNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpsNotificationDispatcher.class);

    static final String DEEP_LINK_TYPE = NotificationType.OPS_ANNOUNCEMENT.name();

    private final OpsNotificationCampaignRepository repo;
    private final OpsCampaignTargetDao targets;
    private final OpsAudienceQuery audience;
    private final NotificationService notificationService;
    private final NotificationPusher pusher;
    private final OpsDispatchPacer pacer;
    private final Clock clock;
    private final OpsNotifyParams params;

    @Autowired
    public OpsNotificationDispatcher(OpsNotificationCampaignRepository repo, OpsCampaignTargetDao targets,
            OpsAudienceQuery audience, NotificationService notificationService, NotificationPusher pusher) {
        this(repo, targets, audience, notificationService, pusher, OpsDispatchPacer.THREAD_SLEEP,
                Clock.systemUTC(), OpsNotifyParams.DEFAULTS);
    }

    /** 测试用：注入记录型 pacer（不真睡）与固定时钟。 */
    public OpsNotificationDispatcher(OpsNotificationCampaignRepository repo, OpsCampaignTargetDao targets,
            OpsAudienceQuery audience, NotificationService notificationService, NotificationPusher pusher,
            OpsDispatchPacer pacer, Clock clock, OpsNotifyParams params) {
        this.repo = repo;
        this.targets = targets;
        this.audience = audience;
        this.notificationService = notificationService;
        this.pusher = pusher;
        this.pacer = pacer;
        this.clock = clock;
        this.params = params;
    }

    /** 立即发送：新建事务提交后接收（事件丢失由扫描 60 秒内兜底）。 */
    @Async("opsNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCampaignReady(OpsCampaignReadyEvent event) {
        dispatch(event.campaignId());
    }

    /** 定时扫描投递入口。重复提交无害（抢占防重）。 */
    @Async("opsNotificationExecutor")
    public void dispatchAsync(long campaignId) {
        dispatch(campaignId);
    }

    /** 发送一个活动。未抢占到（已被抢走 / 已取消 / 未到点）直接返回。 */
    public void dispatch(long campaignId) {
        if (repo.claimForSending(campaignId, clock.instant()) == 0) {
            return;
        }
        try {
            run(campaignId);
        } catch (RuntimeException e) {
            // 意外异常（库抖动等）：不自动重发（D-20），直接收尾 PARTIAL_FAILED；已写入的通知保留。
            log.error("运营通知发送异常中止 campaignId={} cause={}", campaignId, e.getClass().getSimpleName());
            try {
                repo.finish(campaignId, OpsCampaignStatus.PARTIAL_FAILED, clock.instant());
            } catch (RuntimeException ignored) {
                // 收尾也失败：留给超时扫描（15 分钟无进展 → PARTIAL_FAILED）
            }
        }
    }

    private void run(long id) {
        OpsNotificationCampaign campaign = repo.findById(id).orElse(null);
        if (campaign == null) {
            return;
        }
        AudienceSpec spec = AudienceSpec.parse(campaign.getAudienceSpec());
        List<OpsRecipient> recipients = audience.resolve(spec, id);
        log.info("运营通知开始发送 campaignId={} recipients={}", id, recipients.size());

        if (spec.mode() == AudienceSpec.Mode.IDS) {
            int skipped = (int) Math.max(0L, targets.countByCampaign(id) - recipients.size());
            if (repo.setSkipped(id, skipped, clock.instant()) == 0) {
                stopped(id);
                return;
            }
        }
        if (recipients.isEmpty()) {
            finish(id, 0);
            return;
        }

        int batchSize = params.batchSize();
        int batches = (recipients.size() + batchSize - 1) / batchSize;
        int failedTotal = 0;
        for (int b = 0; b < batches; b++) {
            List<OpsRecipient> batch = recipients.subList(b * batchSize,
                    Math.min(recipients.size(), (b + 1) * batchSize));
            BatchOutcome out = sendBatch(id, campaign, batch);
            if (out == null) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted(id);
                } else {
                    stopped(id);
                }
                return;
            }
            failedTotal += out.failed();
            log.info("运营通知批次完成 campaignId={} batch={} written={} failed={}", id, b + 1,
                    out.written(), out.failed());
            if (b < batches - 1 && !pause(params.batchInterval())) {
                interrupted(id);
                return;
            }
        }
        finish(id, failedTotal);
    }

    /** 一批的结果：通知中心写入人数、失败人数（单位：人）。 */
    private record BatchOutcome(int written, int failed) {
    }

    /** 写通知中心的结果：{@code rows == null && !stop} = 重试已尽仍失败；{@code stop} = 必须停止。 */
    private record WriteResult(List<Notification> rows, boolean stop) {
    }

    /**
     * 发一批。返回 {@code null} = 必须停止（活动已不在 SENDING，或线程被中断）。
     * 停止路径尽力把本批已写入但未推送成功的人数计入 {@code failed_count}（条件更新：已被超时扫描改掉的是 no-op）。
     */
    private BatchOutcome sendBatch(long id, OpsNotificationCampaign campaign, List<OpsRecipient> batch) {
        WriteResult wr = writeWithRetry(id, campaign, batch);
        if (wr.stop()) {
            if (Thread.currentThread().isInterrupted()) {
                bestEffortAddFailed(id, batch.size()); // 本批一个都没写进去：整批计失败
            }
            return null;
        }
        List<Notification> written = wr.rows();
        if (written == null) {
            // 写通知中心 3 次重试仍失败：整批计 failed、跳过该批推送、继续下一批。
            if (repo.addFailed(id, batch.size(), clock.instant()) == 0) {
                return null;
            }
            return new BatchOutcome(0, batch.size());
        }
        if (repo.addRecipients(id, written.size(), clock.instant()) == 0) {
            return null;
        }

        int[] pushed = {0};
        int succeeded = 0;
        List<Notification> failed = new ArrayList<>();
        for (Notification n : written) {
            if (Thread.currentThread().isInterrupted()) {
                return abort(id, written.size() - succeeded);
            }
            if (push(n, campaign)) {
                succeeded++;
            } else {
                failed.add(n);
            }
            if (!tick(id, pushed)) {
                return abort(id, written.size() - succeeded);
            }
        }
        // 只重推失败的人（成功的人不重推，防重复打扰）。
        List<Duration> backoffs = params.retryBackoffs();
        for (int round = 0; round < backoffs.size() && !failed.isEmpty(); round++) {
            if (!pause(backoffs.get(round))) {
                return abort(id, written.size() - succeeded);
            }
            List<Notification> still = new ArrayList<>();
            for (Notification n : failed) {
                if (Thread.currentThread().isInterrupted()) {
                    return abort(id, written.size() - succeeded);
                }
                if (push(n, campaign)) {
                    succeeded++;
                } else {
                    still.add(n);
                }
                if (!tick(id, pushed)) {
                    return abort(id, written.size() - succeeded);
                }
            }
            failed = still;
        }
        if (!failed.isEmpty()) {
            if (repo.addFailed(id, failed.size(), clock.instant()) == 0) {
                return null;
            }
        } else if (repo.touchProgress(id, clock.instant()) == 0) {
            return null;
        }
        return new BatchOutcome(written.size(), failed.size());
    }

    /** 停止：尽力把本批未推送成功的人数计入 failed_count，返回 {@code null}。 */
    private BatchOutcome abort(long id, int unfinished) {
        bestEffortAddFailed(id, unfinished);
        return null;
    }

    private void bestEffortAddFailed(long id, int n) {
        if (n <= 0) {
            return;
        }
        try {
            repo.addFailed(id, n, clock.instant()); // 条件更新：已非 SENDING 时影响 0 行，无副作用
        } catch (RuntimeException ignored) {
            // 尽力而为
        }
    }

    /**
     * 写通知中心：整批一个事务；抛异常整批回滚，按退避重试。
     * 每次写之前先 {@code touchProgress}（条件更新）——返回 0 = 已被超时扫描判死，立即停止，
     * 缩小「已判超时仍写一整批」的窗口。
     */
    private WriteResult writeWithRetry(long id, OpsNotificationCampaign campaign, List<OpsRecipient> batch) {
        List<Duration> backoffs = params.retryBackoffs();
        for (int attempt = 0; ; attempt++) {
            if (repo.touchProgress(id, clock.instant()) == 0) {
                return new WriteResult(null, true);
            }
            try {
                return new WriteResult(notificationService.writeOpsAnnouncements(campaign, batch), false);
            } catch (RuntimeException e) {
                if (attempt >= backoffs.size()) {
                    log.warn("运营通知批次写入通知中心失败（重试已尽） campaignId={} cause={}",
                            id, e.getClass().getSimpleName());
                    return new WriteResult(null, false);
                }
                if (!pause(backoffs.get(attempt))) {
                    return new WriteResult(null, true);
                }
            }
        }
    }

    /** 推送文案直接用该行 title / body（与通知中心同一语言）。 */
    private boolean push(Notification n, OpsNotificationCampaign campaign) {
        return pusher.pushToUserNow(n.getRecipientUserId(), n.getTitle(), n.getBody(), DEEP_LINK_TYPE,
                n.getDeepLinkToken(), campaign.getPublicToken());
    }

    /** 推送计数 +1，每满 {@code progressEvery} 刷一次进展。返回 false = 必须停止。 */
    private boolean tick(long id, int[] pushed) {
        pushed[0]++;
        return pushed[0] % params.progressEvery() != 0 || repo.touchProgress(id, clock.instant()) != 0;
    }

    /** 等待；线程被中断返回 false（pacer 已恢复中断标志）。 */
    private boolean pause(Duration d) {
        pacer.sleep(d);
        return !Thread.currentThread().isInterrupted();
    }

    private void finish(long id, int failedTotal) {
        OpsCampaignStatus status = failedTotal > 0 ? OpsCampaignStatus.PARTIAL_FAILED : OpsCampaignStatus.DONE;
        int n = repo.finish(id, status, clock.instant());
        log.info("运营通知发送结束 campaignId={} status={}", id, n > 0 ? status : "UNCHANGED");
    }

    private void stopped(long id) {
        log.warn("运营通知已不在 SENDING（超时扫描已处置），停止后续批次 campaignId={}", id);
    }

    private void interrupted(long id) {
        log.warn("运营通知发送线程被中断，停止发送 campaignId={}", id);
        try {
            repo.finish(id, OpsCampaignStatus.PARTIAL_FAILED, clock.instant());
        } catch (RuntimeException ignored) {
            // 留给超时扫描
        }
    }
}
