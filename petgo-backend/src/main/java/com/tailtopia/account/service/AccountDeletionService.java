package com.tailtopia.account.service;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.domain.DeletionStatus;
import com.tailtopia.account.event.AccountDeletionRequestedEvent;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.service.AuthAccountDeletionService;
import com.tailtopia.consult.service.ConsultAnonymizationService;
import com.tailtopia.notify.service.NotificationDeletionService;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.media.MediaDeletionService;
import com.tailtopia.shared.media.PersonalMedia;
import com.tailtopia.triage.service.TriageDeletionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号注销编排（Story 7.3，AccountDeletionJob，决策 D1/D2）。
 *
 * <p>DB 状态机驱动可靠异步作业（PENDING→PROCESSING→DONE/FAILED + retry_count + 启动重扫，<b>禁 MQ</b>）：
 * 跨各 owning service <b>删除</b>纯个人数据（users/pet_profiles/health/triage/notifications + 全部个人 OSS 图 +
 * IM 聊天媒体 + Redis 痕迹），<b>匿名化保留</b> UGC（content 经 user 行删除自动匿名）+ consult 会话/评分（剥 PII）。
 * 半途失败 FAILED+retry，不留半删当成功；幂等可重跑。日志只记代理 id+进度+计数，绝不落 PII。
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final AccountDeletionRepository deletions;
    private final ProfileDeletionService profileDeletion;
    private final TriageDeletionService triageDeletion;
    private final ConsultAnonymizationService consultAnonymization;
    private final NotificationDeletionService notificationDeletion;
    private final AuthAccountDeletionService authDeletion;
    private final MediaDeletionService mediaDeletion;
    private final TencentImClient imClient;
    private final ApplicationEventPublisher events;

    public AccountDeletionService(AccountDeletionRepository deletions,
            ProfileDeletionService profileDeletion, TriageDeletionService triageDeletion,
            ConsultAnonymizationService consultAnonymization,
            NotificationDeletionService notificationDeletion, AuthAccountDeletionService authDeletion,
            MediaDeletionService mediaDeletion, TencentImClient imClient,
            ApplicationEventPublisher events) {
        this.deletions = deletions;
        this.profileDeletion = profileDeletion;
        this.triageDeletion = triageDeletion;
        this.consultAnonymization = consultAnonymization;
        this.notificationDeletion = notificationDeletion;
        this.authDeletion = authDeletion;
        this.mediaDeletion = mediaDeletion;
        this.imClient = imClient;
        this.events = events;
    }

    /** 受理注销（双重确认在 web 层校验）：登记 PENDING（幂等）+ 发事件触发异步作业（AFTER_COMMIT）。 */
    @Transactional
    public void requestDeletion(long userId) {
        AccountDeletion deletion = deletions.findByUserId(userId)
                .orElseGet(() -> deletions.save(AccountDeletion.request(userId)));
        events.publishEvent(new AccountDeletionRequestedEvent(deletion.getId()));
    }

    /** 受理提交后（事务提交）异步执行级联作业，失败置 FAILED 由启动重扫续跑。 */
    @Async
    @TransactionalEventListener
    public void onRequested(AccountDeletionRequestedEvent event) {
        try {
            execute(event.deletionId());
        } catch (RuntimeException e) {
            markFailed(event.deletionId(), e);
        }
    }

    /** 执行级联删除/匿名化。各模块各自事务；编排本身分步，失败抛出由调用方置 FAILED 重试。 */
    public void execute(long deletionId) {
        AccountDeletion d = deletions.findById(deletionId).orElse(null);
        if (d == null || d.getStatus() == DeletionStatus.DONE) {
            return;
        }
        long userId = d.getUserId();
        setProcessing(deletionId);

        // 删除/匿名化各模块，收集待删个人媒体（content 由 user 行删除自动匿名，无需单独调用）。
        PersonalMedia media = PersonalMedia.empty()
                .merge(profileDeletion.deleteByUserId(userId))
                .merge(triageDeletion.deleteByUserId(userId))
                .merge(consultAnonymization.anonymizeByUserId(userId));
        notificationDeletion.deleteByUserId(userId);
        // auth 最后删（用户行删除后 UGC 即匿名）；收头像图。
        media = media.merge(authDeletion.deleteByUserId(userId));

        // OSS 个人图删除（私密②全删；公开①仅头像/名片个人图，UGC 帖子图不在此列）。
        mediaDeletion.deletePrivateKeys(media.privateKeys());
        mediaDeletion.deletePublicByUrls(media.publicUrls());
        // IM 聊天媒体（决策 D2）。
        imClient.deleteUserConversationMedia(ImAccountMapper.userImId(userId));

        markDone(deletionId);
        log.info("账号注销完成 deletionId={} privateImgs={} publicImgs={}",
                deletionId, media.privateKeys().size(), media.publicUrls().size());
    }

    @Transactional
    protected void setProcessing(long deletionId) {
        deletions.findById(deletionId).ifPresent(d -> {
            d.markProcessing();
            deletions.save(d);
        });
    }

    @Transactional
    protected void markDone(long deletionId) {
        deletions.findById(deletionId).ifPresent(d -> {
            d.markDone();
            deletions.save(d);
        });
    }

    @Transactional
    protected void markFailed(long deletionId, RuntimeException e) {
        log.warn("账号注销失败 deletionId={} cause={}", deletionId, e.getClass().getSimpleName());
        deletions.findById(deletionId).ifPresent(d -> {
            d.markFailed();
            deletions.save(d);
        });
    }

    /** 启动重扫：续跑崩溃/重启遗留的未完成注销作业（不丢、不半残）。 */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void rescanOnStartup() {
        List<AccountDeletion> residual = deletions.findByStatusIn(
                List.of(DeletionStatus.PENDING, DeletionStatus.PROCESSING, DeletionStatus.FAILED));
        for (AccountDeletion d : residual) {
            try {
                execute(d.getId());
            } catch (RuntimeException e) {
                markFailed(d.getId(), e);
            }
        }
    }
}
