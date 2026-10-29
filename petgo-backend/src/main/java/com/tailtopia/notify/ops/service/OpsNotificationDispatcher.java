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
import com.tailtopia.notify.preference.PushCategory;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.notify.service.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * 仍失败整批计 failed 且跳过推送）→ 逐人同步推送（<b>失败不重推</b>，只计数，防重复收到，bug 604）→
 * 批间等 1 秒 → 收尾。
 *
 * <p>V1.4.0 ④ Story 1.2（AD-5 / P-4）：每批写入通知中心之后、推送之前，一次查出本批里关闭了「活动与优惠」（PROMO）的人，
 * 他们<b>只留通知中心</b>：不推送、不计推送进展、不计失败；{@code recipient_count}（写入通知中心人数）照常包含他们。
 *
 * <p>本身不加事务；每一步各自短事务（仓储方法自带 / {@code writeOpsAnnouncements} REQUIRES_NEW）。
 * 已写入通知中心的行任何情况下都不回滚、不删除（PRD AC6）。
 *
 * <p>🔒 日志只记活动 id、批次序号、本批写入数、本批失败数、本批被推送开关拦下的人数、最终状态；不记收件人 id、标题、正文、token。
 */
@Service
public class OpsNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpsNotificationDispatcher.class);

    static final String DEEP_LINK_TYPE = NotificationType.OPS_ANNOUNCEMENT.name();

    /** 运营公告所属推送分类（映射只在 {@link PushCategory#of}，此处不另写 —— 当前为 PROMO「活动与优惠」）。 */
    private static final PushCategory PUSH_CATEGORY = PushCategory.of(NotificationType.OPS_ANNOUNCEMENT);

    private final OpsNotificationCampaignRepository repo;
    private final OpsCampaignTargetDao targets;
    private final OpsAudienceQuery audience;
    private final NotificationService notificationService;
    private final NotificationPusher pusher;
    private final PushPreferenceService prefs;
    private final OpsDispatchPacer pacer;
    private final Clock clock;
    private final OpsNotifyParams params;

    @Autowired
    public OpsNotificationDispatcher(OpsNotificationCampaignRepository repo, OpsCampaignTargetDao targets,
            OpsAudienceQuery audience, NotificationService notificationService, NotificationPusher pusher,
            PushPreferenceService prefs) {
        this(repo, targets, audience, notificationService, pusher, prefs, OpsDispatchPacer.THREAD_SLEEP,
                Clock.systemUTC(), OpsNotifyParams.DEFAULTS);
    }

    /** 测试用：注入记录型 pacer（不真睡）与固定时钟。 */
    public OpsNotificationDispatcher(OpsNotificationCampaignRepository repo, OpsCampaignTargetDao targets,
            OpsAudienceQuery audience, NotificationService notificationService, NotificationPusher pusher,
            PushPreferenceService prefs, OpsDispatchPacer pacer, Clock clock, OpsNotifyParams params) {
        this.repo = repo;
        this.targets = targets;
        this.audience = audience;
        this.notificationService = notificationService;
        this.pusher = pusher;
        this.prefs = prefs;
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
        // 发送中行「已写入 / 计划」的分母（V1.3.4 AB-31 返工）：按实际发送时刻的人群写入
        if (repo.setPlanned(id, recipients.size(), clock.instant()) == 0) {
            stopped(id);
            return;
        }

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
            log.info("运营通知批次完成 campaignId={} batch={} written={} failed={} muted={}", id, b + 1,
                    out.written(), out.failed(), out.muted());
            if (b < batches - 1 && !pause(params.batchInterval())) {
                interrupted(id);
                return;
            }
        }
        finish(id, failedTotal);
    }

    /** 一批的结果：通知中心写入人数、失败人数、被推送开关拦下的人数（单位：人）。 */
    private record BatchOutcome(int written, int failed, int muted) {
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
            return new BatchOutcome(0, batch.size(), 0);
        }
        if (repo.addRecipients(id, written.size(), clock.instant()) == 0) {
            return null;
        }
        // V1.4.0 ④ AD-5 / P-4：关了「活动与优惠」的人只留通知中心 —— 不推送、不 tick、不计失败。
        Set<Long> muted = mutedAmong(id, written);
        List<Notification> toPush = muted.isEmpty() ? written
                : written.stream().filter(n -> !muted.contains(n.getRecipientUserId())).toList();

        int[] pushed = {0};
        int succeeded = 0;
        List<Notification> failed = new ArrayList<>();
        for (Notification n : toPush) {
            if (Thread.currentThread().isInterrupted()) {
                return abort(id, toPush.size() - succeeded);
            }
            if (push(n, campaign)) {
                succeeded++;
            } else {
                failed.add(n);
            }
            if (!tick(id, pushed)) {
                return abort(id, toPush.size() - succeeded);
            }
        }
        // 推送失败一律不重推（bug 604，2026-10-29 拍板）：超时 / 连接断开时腾讯可能已投递，
        // 重推会让用户收到两条——重复收到比没收到更糟。失败只计数；通知中心那条已写入，用户仍能看到。
        if (!failed.isEmpty()) {
            if (repo.addFailed(id, failed.size(), clock.instant()) == 0) {
                return null;
            }
        } else if (repo.touchProgress(id, clock.instant()) == 0) {
            return null;
        }
        return new BatchOutcome(written.size(), failed.size(), written.size() - toPush.size());
    }

    /** 本批里关闭了运营公告所属分类（PROMO）推送的收件人（一次查询）。查询异常 → 视为无人关闭、照常全推（宁多勿漏）。 */
    private Set<Long> mutedAmong(long id, List<Notification> written) {
        try {
            return prefs.mutedAmong(written.stream().map(Notification::getRecipientUserId).toList(),
                    PUSH_CATEGORY);
        } catch (RuntimeException e) {
            log.warn("推送偏好批量查询失败（本批照常推送）campaignId={} cause={}", id, e.getClass().getSimpleName());
            return Set.of();
        }
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
