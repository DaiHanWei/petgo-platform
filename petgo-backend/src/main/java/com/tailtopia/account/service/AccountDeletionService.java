package com.tailtopia.account.service;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.domain.DeletionStatus;
import com.tailtopia.account.event.AccountDeletionRequestedEvent;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.moderation.service.ManualReviewService;
import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.auth.service.AuthAccountDeletionService;
import com.tailtopia.collect.service.CollectDeletionService;
import com.tailtopia.consult.service.ConsultAnonymizationService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.ContentShareService;
import com.tailtopia.growth.service.GrowthAccountDeletionService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.service.NotificationDeletionService;
import com.tailtopia.ops.popup.service.OpsPopupImpressionDeletionService;
import com.tailtopia.pay.service.PawCoinAccountDeletionService;
import com.tailtopia.mention.service.MentionCandidateMaintenanceService;
import com.tailtopia.place.service.PlaceCommentService;
import com.tailtopia.place.service.PlacePhotoService;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.profile.slot.PetSlotAccountDeletionService;
import com.tailtopia.share.service.ShareRewardDeletionService;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.media.MediaDeletionService;
import com.tailtopia.shared.media.PersonalMedia;
import com.tailtopia.shop.service.ShopAccountDeletionService;
import com.tailtopia.social.service.FollowService;
import com.tailtopia.tailsonality.service.TailsonalityDeletionService;
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
    // V1.3.0 批次 A Story 5.4：一次性引导标记（纯个人数据，物理删）。
    private final com.tailtopia.onboarding.service.OnboardingMarkDeletionService onboardingMarkDeletion;
    private final PlaceCommentService placeCommentService;
    private final PlacePhotoService placePhotoService;
    /** V1.3.0 batch-b1 Story 3.1：@ 候选集（派生表，两个方向都物理删）。 */
    private final MentionCandidateMaintenanceService mentionCandidateMaintenance;
    private final TailsonalityDeletionService tailsonalityDeletion;
    /** V1.3.4 follow Story 1.1：关注关系两个方向显式清理 + 扣对端计数（AD-1）。 */
    private final FollowService followService;
    /** V1.3.4 Story 5.1：运营弹窗曝光（登录用户的频次记录，纯个人数据，物理删）。 */
    private final OpsPopupImpressionDeletionService opsPopupImpressionDeletion;
    /** V1.4.0 ① Story 1.2：成长值账户 / 流水 / 每日额度（AD-18，物理删）。 */
    private final GrowthAccountDeletionService growthDeletion;
    /** V1.4.0 ④ Story 1.1：推送分类开关（账号级纯个人数据，物理删）。 */
    private final PushPreferenceService pushPreferences;
    /** V1.4.0 ④ Story 2.1：帖子 / 场所收藏（仅本人可见的私人数据，物理删）。 */
    private final CollectDeletionService collectDeletion;
    /** OCR #2：宠物名额 ACTIVE → EXPIRED（保留付费记录），否则到期扫描继续给已注销账号写通知 / 推送。 */
    private final PetSlotAccountDeletionService petSlotDeletion;
    /** 专题线 video Story 1.1：注销时移出视频免审名单。 */
    private final VideoTrustedPublisherAdminService videoTrustedPublishers;

    public AccountDeletionService(AccountDeletionRepository deletions,
            ProfileDeletionService profileDeletion, TriageDeletionService triageDeletion,
            ConsultAnonymizationService consultAnonymization,
            NotificationDeletionService notificationDeletion,
            PawCoinAccountDeletionService pawCoinDeletion, AuthAccountDeletionService authDeletion,
            MediaDeletionService mediaDeletion, TencentImClient imClient,
            ApplicationEventPublisher events, ContentService contentService,
            ManualReviewService reviewService, ViolationCountService violationCountService,
            ShopAccountDeletionService shopDeletion, ContentShareService contentShareService,
            ShareRewardDeletionService shareRewardDeletion,
            com.tailtopia.onboarding.service.OnboardingMarkDeletionService onboardingMarkDeletion,
            PlaceCommentService placeCommentService, PlacePhotoService placePhotoService,
            MentionCandidateMaintenanceService mentionCandidateMaintenance,
            TailsonalityDeletionService tailsonalityDeletion, FollowService followService,
            OpsPopupImpressionDeletionService opsPopupImpressionDeletion,
            GrowthAccountDeletionService growthDeletion,
            PushPreferenceService pushPreferences, CollectDeletionService collectDeletion,
            PetSlotAccountDeletionService petSlotDeletion,
            VideoTrustedPublisherAdminService videoTrustedPublishers) {
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
        this.onboardingMarkDeletion = onboardingMarkDeletion;
        this.placeCommentService = placeCommentService;
        this.mentionCandidateMaintenance = mentionCandidateMaintenance;
        this.placePhotoService = placePhotoService;
        this.tailsonalityDeletion = tailsonalityDeletion;
        this.followService = followService;
        this.opsPopupImpressionDeletion = opsPopupImpressionDeletion;
        this.growthDeletion = growthDeletion;
        this.pushPreferences = pushPreferences;
        this.collectDeletion = collectDeletion;
        this.petSlotDeletion = petSlotDeletion;
        this.videoTrustedPublishers = videoTrustedPublishers;
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
                .merge(profileDeletion.deleteAllForAccount(userId)) // V1.4.0 multi-pet：逐只删全部宠物，最后按账号给身份证打标
                .merge(triageDeletion.deleteByUserId(userId))
                .merge(consultAnonymization.anonymizeByUserId(userId));
        // OCR #2：宠物已全部删除 → 名额终止（ACTIVE → EXPIRED，保留付费记录）。⚠️ 必须在通知删除【前】：
        // 否则删完通知到这一步之间，到期扫描还能再给他写一条。
        petSlotDeletion.expireAllForAccount(userId);
        notificationDeletion.deleteByUserId(userId);

        // 内容审核 story 9 注销联动（§5.5）：必须在 user 行删除【前】完成——此时 author_id 仍可识别其内容。
        //  ① 帖子/评论对他人隐藏（AUTHOR_DEACTIVATED，保留匿名化，可见性层≠7-3显示层匿名化，D-CM4）；
        //  ② 人工审核队列 PENDING 条目移出（不再发布，内容随注销对所有人不可见）；
        //  ③ 违规计数行随注销删除（D1/D2；处置审计证据留 admin_audit_logs，§5.5 R2）。各自事务、幂等可重跑。
        contentService.deactivateAuthorContent(userId);
        reviewService.removePendingForAuthor(userId);
        violationCountService.deleteByAccount(userId);

        // V1.3.0 batch-b1 Story 1.7：场所评论同一口径（AUTHOR_DEACTIVATED，对他人隐藏）。
        // 🔴 **不能漏**：场所评论是独立表（AD-8），content 那条级联碰不到它 ——
        //    漏掉的结果是注销用户的场所评论继续挂着他的身份对所有人可见（违反 D1/D2）。
        //    ⚠️ 同样必须在 user 行删除【前】：那之后 author_id 认不出人。
        //    这里**不删行**：评论内容是场所攻略的一部分，消失的是身份展示，不是攻略。
        placeCommentService.deactivateAuthorComments(userId);
        // Story 1.9：他**补充**的场所照片同样对他人隐藏。
        // ⚠️ 他作为**标记人**提交的那批**不隐藏** —— 那是场所条目本身的资料（首图 / OG 预览图
        //    都取它），随人一起隐藏会把整个场所变成无图条目。那批的身份匿名化由
        //    详情页的 markedBy 投影完成（显示「已注销用户」）。
        placePhotoService.deactivateUploaderPhotos(userId);

        // V1.3.0 batch-b1 Story 3.1：@ 候选集**两个方向都物理删**（D1/D2）。
        // 🔴 只删他自己那份的话，他还会继续出现在**别人**的 @ 候选里 ——
        //    一个已注销的账号被 @ 出来，点进去是「用户不存在」。
        // ⚠️ 这张表是 comments / content_likes 的派生物，不是 UGC ——
        //    所以是**删行**而不是像场所评论那样只做匿名化。
        mentionCandidateMaintenance.purgeUser(userId);

        // 1.1.6 电商/分享注销联动（D1/D2 口径，同样须在 user 行匿名化【前】——此时 user_id 仍可识别）：
        //  ① shipping_addresses / shop_carts 纯个人数据物理删除；shop_orders 照 consult_orders 例
        //     保留交易记录、剥收货快照 PII；return_requests 流程保留、加密收款账号置空；
        //  ② content_shares 随作者内容一并删除（F14：分享链接注销即失效，token 不该比内容活得久）；
        //  ③ id_card_share_rewards / share_reward_quotas 随 PawCoin 钱包口径物理删除（先删奖励留痕，再作废钱包）。
        // 各自事务、幂等可重跑。
        shopDeletion.deleteByUserId(userId);
        contentShareService.deleteByAuthorForAccountDeletion(userId);
        shareRewardDeletion.deleteByUserId(userId);
        // V1.3.0 批次 A Story 5.4：一次性引导标记（谁看过哪个引导）随注销物理删除。
        // ⚠️ 漏接的表会在注销后留着指向一个已不存在的人的行，而这类遗漏没有任何报错提醒。
        onboardingMarkDeletion.deleteByUserId(userId);
        // V1.3.4 Story 5.1：运营弹窗曝光行随注销物理删除（D1/D2）。
        // ⚠️ users 行只匿名化不删，ops_popup_impressions 的 FK ON DELETE CASCADE 在这条路径上不会触发 —— 必须显式删。
        opsPopupImpressionDeletion.deleteByUserId(userId);
        // V1.4.0 ④ Story 1.1：推送设置是账号级个人数据；users 行只匿名化不删，FK CASCADE 在注销路径不触发 ——
        // 必须显式删，且在 authDeletion 之前。
        pushPreferences.deleteByUserId(userId);
        // V1.3.2 Story 2.5：Tailsonality 主人类型是账号级个人数据（AD-17），须在 user 行删除前物理删除
        // （tailsonality_owner_types.user_id 对 users 有 FK、无 ON DELETE）。宠物级的结果行已随上面的 profileDeletion 删掉。
        tailsonalityDeletion.deleteOwnerTypeByUserId(userId);
        // V1.4.0 ① Story 1.2：成长值账户 / 流水 / 每日额度物理删除（AD-18、PRD AC10）。
        // ⚠️ users 行只匿名化不删，growth_accounts 对 users 的 FK 永不触发 CASCADE —— 必须显式删；
        //    放在 tailsonality 之后、PawCoin 作废之前（架构 AD-18 定的位置）。
        growthDeletion.deleteByUserId(userId);
        // V1.4.0 ④ Story 2.1：本人的帖子 / 场所收藏物理删（users 行只匿名化，FK CASCADE 不触发），在 authDeletion 之前。
        // ⚠️ 他人收藏的、指向本人帖子的行不删：帖子置 AUTHOR_DEACTIVATED 后列表读时显示不可见，由对方自行移除。
        collectDeletion.deleteByUserId(userId);

        // PawCoin 余额作废（Story 1.6，FR-50D）：写 FORFEITURE 终结分录归零 + 物理删钱包/流水；在删 user 行前。
        pawCoinDeletion.voidBalanceAndPurge(userId);
        // auth 最后删（用户行删除后 UGC 即匿名）；收头像图。
        media = media.merge(authDeletion.deleteByUserId(userId));
        // V1.3.4 follow Story 1.1：关注关系两个方向删行 + 扣对端计数（AD-1）。
        // 🔴 **不能靠 FK 的 ON DELETE CASCADE**：注销是就地匿名化，users 行不删，CASCADE 永远不触发。
        // ⚠️ 放在 authDeletion **之后**：此时 deleted_at 已写，findActiveByPublicToken 已拒绝新的关注，
        //    清理后不会再长出新行（放在之前会有一个「清完又被人关注」的窗口）。幂等可重跑。
        followService.purgeUser(userId);
        // 专题线 video Story 1.1：移出视频免审名单（D1/D2）。
        // 🔴 同上，不能靠 FK CASCADE（users 行不删）。漏掉的后果：已注销账号永久留在名单里，
        //    而注销后用户抽屉只读、运营在后台无从移出（code-review 2026-10-16）。幂等可重跑。
        videoTrustedPublishers.purgeForAccountDeletion(userId);

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
