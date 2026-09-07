package com.tailtopia.account.service;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.domain.DeletionStatus;
import com.tailtopia.account.event.AccountDeletionRequestedEvent;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.moderation.service.ManualReviewService;
import com.tailtopia.auth.service.AuthAccountDeletionService;
import com.tailtopia.consult.service.ConsultAnonymizationService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.ContentShareService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.notify.service.NotificationDeletionService;
import com.tailtopia.pay.service.PawCoinAccountDeletionService;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.share.service.ShareRewardDeletionService;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.media.MediaDeletionService;
import com.tailtopia.shared.media.PersonalMedia;
import com.tailtopia.shop.service.ShopAccountDeletionService;
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
    private final PawCoinAccountDeletionService pawCoinDeletion;
    private final AuthAccountDeletionService authDeletion;
    private final MediaDeletionService mediaDeletion;
    private final TencentImClient imClient;
    private final ApplicationEventPublisher events;
    // 内容审核 story 9 注销联动：内容隐藏 + 队列移除 + 违规计数删除。
    private final ContentService contentService;
    private final ManualReviewService reviewService;
    private final ViolationCountService violationCountService;
    // 1.1.6 电商/分享注销联动：shop 地址/购物车删除 + 订单/退货剥 PII + 分享行与奖励留痕清理。
    private final ShopAccountDeletionService shopDeletion;
    private final ContentShareService contentShareService;
    private final ShareRewardDeletionService shareRewardDeletion;

    public AccountDeletionService(AccountDeletionRepository deletions,
            ProfileDeletionService profileDeletion, TriageDeletionService triageDeletion,
            ConsultAnonymizationService consultAnonymization,
            NotificationDeletionService notificationDeletion,
            PawCoinAccountDeletionService pawCoinDeletion, AuthAccountDeletionService authDeletion,
            MediaDeletionService mediaDeletion, TencentImClient imClient,
            ApplicationEventPublisher events, ContentService contentService,
            ManualReviewService reviewService, ViolationCountService violationCountService,
            ShopAccountDeletionService shopDeletion, ContentShareService contentShareService,
            ShareRewardDeletionService shareRewardDeletion) {
        this.deletions = deletions;
        this.profileDeletion = profileDeletion;
        this.triageDeletion = triageDeletion;
        this.consultAnonymization = consultAnonymization;
        this.notificationDeletion = notificationDeletion;
        this.pawCoinDeletion = pawCoinDeletion;
        this.authDeletion = authDeletion;
        this.mediaDeletion = mediaDeletion;
        this.imClient = imClient;
        this.events = events;
        this.contentService = contentService;
        this.reviewService = reviewService;
        this.violationCountService = violationCountService;
        this.shopDeletion = shopDeletion;
        this.contentShareService = contentShareService;
        this.shareRewardDeletion = shareRewardDeletion;
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

        // 内容审核 story 9 注销联动（§5.5）：必须在 user 行删除【前】完成——此时 author_id 仍可识别其内容。
        //  ① 帖子/评论对他人隐藏（AUTHOR_DEACTIVATED，保留匿名化，可见性层≠7-3显示层匿名化，D-CM4）；
        //  ② 人工审核队列 PENDING 条目移出（不再发布，内容随注销对所有人不可见）；
        //  ③ 违规计数行随注销删除（D1/D2；处置审计证据留 admin_audit_logs，§5.5 R2）。各自事务、幂等可重跑。
        contentService.deactivateAuthorContent(userId);
        reviewService.removePendingForAuthor(userId);
        violationCountService.deleteByAccount(userId);

        // 1.1.6 电商/分享注销联动（D1/D2 口径，同样须在 user 行匿名化【前】——此时 user_id 仍可识别）：
        //  ① shipping_addresses / shop_carts 纯个人数据物理删除；shop_orders 照 consult_orders 例
        //     保留交易记录、剥收货快照 PII；return_requests 流程保留、加密收款账号置空；
        //  ② content_shares 随作者内容一并删除（F14：分享链接注销即失效，token 不该比内容活得久）；
        //  ③ id_card_share_rewards / share_reward_quotas 随 PawCoin 钱包口径物理删除（先删奖励留痕，再作废钱包）。
        // 各自事务、幂等可重跑。
        shopDeletion.deleteByUserId(userId);
        contentShareService.deleteByAuthorForAccountDeletion(userId);
        shareRewardDeletion.deleteByUserId(userId);

        // PawCoin 余额作废（Story 1.6，FR-50D）：写 FORFEITURE 终结分录归零 + 物理删钱包/流水；在删 user 行前。
        pawCoinDeletion.voidBalanceAndPurge(userId);
        // auth 最后删（用户行删除后 UGC 即匿名）；收头像图。
        media = media.merge(authDeletion.deleteByUserId(userId));

        // OSS 个人图：2026-08-19 起保留不删（F21，快照引用保护；MediaDeletionService 只记账）。
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
