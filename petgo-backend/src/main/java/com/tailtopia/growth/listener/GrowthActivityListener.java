package com.tailtopia.growth.listener;

import com.tailtopia.auth.event.UserDailyActiveEvent;
import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.content.event.ContentRemovedEvent;
import com.tailtopia.content.event.ContentUncollectedEvent;
import com.tailtopia.content.event.ContentUnlikedEvent;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.place.event.PlaceCheckedInEvent;
import com.tailtopia.profile.event.MilestoneCompletedEvent;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 日常行为 → 成长值（V1.4.0 ① Story 1.1 · 架构 AD-6）。登录 / 发帖 / 评论 / 点赞 / 取消点赞 / 打卡 / 里程碑 /
 * 收藏 / 取消收藏 / 违规下架。
 *
 * <p>🔴 本类<b>只拼命令</b>：参与判定、规则启停、每日额度、幂等全在 {@link GrowthLedgerService}；
 * 这里只做「事件语义」过滤（自评、自收藏）。幂等键只经 {@link GrowthIdemKeys}。
 *
 * <p>写法：{@code @Async} + {@code @TransactionalEventListener}（AFTER_COMMIT，回滚的业务不入账）；每条命令由
 * {@code GrowthLedgerService} 自开事务——同一事件的两条命令（如评论人 / 帖子作者）互不连坐。
 * 例外：{@link UserDailyActiveEvent} 由请求 filter 在事务外发布，用 {@code @Async @EventListener}。
 * 失败只记 ERROR（userId / 规则代码 / 异常类名），<b>不重试</b>（V1 姿态，运营可手动补）；禁止记评论原文、昵称。
 *
 * <p>作者自删帖、评论被删不扣（这些路径不发 {@link ContentRemovedEvent}）；违规下架后 {@code restore} 不补回（P-1）。
 */
@Component
public class GrowthActivityListener {

    private static final Logger log = LoggerFactory.getLogger(GrowthActivityListener.class);

    static final String REF_CONTENT_POST = "CONTENT_POST";
    static final String REF_PLACE_CHECKIN = "PLACE_CHECKIN";
    static final String REF_MILESTONE = "MILESTONE";

    private final GrowthLedgerService ledger;
    private final ContentPetQuery contentPets;
    private final ContentLikeRepository likes;
    private final Clock clock;

    @Autowired
    public GrowthActivityListener(GrowthLedgerService ledger, ContentPetQuery contentPets,
            ContentLikeRepository likes) {
        this(ledger, contentPets, likes, Clock.systemUTC());
    }

    GrowthActivityListener(GrowthLedgerService ledger, ContentPetQuery contentPets, ContentLikeRepository likes,
            Clock clock) {
        this.ledger = ledger;
        this.contentPets = contentPets;
        this.likes = likes;
        this.clock = clock;
    }

    /** 每日登录（PRD AC1）。occurredAt 取该 WIB 日零点，保证额度日 = 事件日。 */
    @Async
    @EventListener
    public void onDailyActive(UserDailyActiveEvent e) {
        accrue(new AccrualCommand(e.userId(), RuleCode.DAILY_LOGIN, null,
                GrowthIdemKeys.dailyLogin(e.userId(), e.wibDate()), null, null, null,
                e.wibDate().atStartOfDay(ScheduleWindow.WIB).toInstant()));
    }

    /**
     * 发帖（PRD AC15）：私密 Diary 照常；同帖二次发布被 {@code POST:{postId}} 挡住。
     * 关联宠物取事件的 {@code petIds}（多宠物线合入后的全部关联宠物，CROSS-STORY 2026-10-18「其他口径」）。
     */
    @Async
    @TransactionalEventListener
    public void onPublished(ContentPublishedEvent e) {
        accrue(new AccrualCommand(e.authorId(), RuleCode.POST_PUBLISHED, null, GrowthIdemKeys.post(e.postId()),
                REF_CONTENT_POST, String.valueOf(e.postId()), petIds(e.petIds()), orNow(e.createdAt())));
    }

    /**
     * 评论 / 回复（PRD AC15、P-2）：评论人 = 帖子作者 → 两边都不给、不占额度；{@code COMMENT_RECEIVED} 只给帖子作者，
     * 不给被回复者；帖已消失（{@code contentAuthorId = -1}）→ 不发 {@code COMMENT_RECEIVED}。
     */
    @Async
    @TransactionalEventListener
    public void onCommented(ContentCommentedEvent e) {
        if (e.commenterId() == e.contentAuthorId()) {
            return;
        }
        List<Long> pets = postPetIds(e.postId());
        String ref = String.valueOf(e.postId());
        Instant at = orNow(e.createdAt());
        accrue(new AccrualCommand(e.commenterId(), RuleCode.COMMENT_POSTED, null,
                GrowthIdemKeys.commentPosted(e.commentId()), REF_CONTENT_POST, ref, pets, at));
        if (e.contentAuthorId() > 0) {
            accrue(new AccrualCommand(e.contentAuthorId(), RuleCode.COMMENT_RECEIVED, null,
                    GrowthIdemKeys.commentReceived(e.commentId()), REF_CONTENT_POST, ref, pets, at));
        }
    }

    /**
     * 点赞（PRD AC3）：点赞人 {@code LIKE_GIVEN}、作者 {@code LIKE_RECEIVED}，键不含时间 = 终身一次。
     *
     * <p>⚠️ 入账后复查点赞是否还在：点赞与取消点赞的两个异步任务没有先后保证，取消的扣回若先跑会找不到原流水而空转，
     * 随后入账提交 → 分数永久留下（键终身一次，之后再赞再取消也补不回）。入账提交后若点赞行已不在，由这里补扣
     * （与取消点赞同一 sourceKey，两边谁先到都只扣一次）。
     */
    @Async
    @TransactionalEventListener
    public void onLiked(ContentLikedEvent e) {
        if (e.likerId() == e.authorId()) {
            return; // 自赞 content 侧本就不发事件；这里再挡一层。
        }
        List<Long> pets = postPetIds(e.postId());
        String ref = String.valueOf(e.postId());
        Instant at = orNow(e.createdAt());
        accrue(new AccrualCommand(e.likerId(), RuleCode.LIKE_GIVEN, null,
                GrowthIdemKeys.likeGiven(e.likerId(), e.postId()), REF_CONTENT_POST, ref, pets, at));
        accrue(new AccrualCommand(e.authorId(), RuleCode.LIKE_RECEIVED, null,
                GrowthIdemKeys.likeReceived(e.likerId(), e.postId()), REF_CONTENT_POST, ref, pets, at));
        boolean stillLiked;
        try {
            stillLiked = likes.existsByPostIdAndUserId(e.postId(), e.likerId());
        } catch (RuntimeException ex) {
            stillLiked = true; // 复查失败按「仍在」处理：宁可少扣，不误扣。
        }
        if (!stillLiked) {
            reverseLike(e.postId(), e.likerId(), e.authorId());
        }
    }

    /** 取消点赞（PRD AC14）：两条各按原键扣回；当初没入账的不扣；不返还当天点赞额度。 */
    @Async
    @TransactionalEventListener
    public void onUnliked(ContentUnlikedEvent e) {
        reverseLike(e.postId(), e.likerId(), e.authorId());
    }

    private void reverseLike(long postId, long likerId, long authorId) {
        reverse(new ReverseCommand(GrowthIdemKeys.likeGiven(likerId, postId), ReverseReason.UNLIKE,
                GrowthIdemKeys.SOURCE_UNLIKE, null), likerId, RuleCode.LIKE_GIVEN);
        reverse(new ReverseCommand(GrowthIdemKeys.likeReceived(likerId, postId), ReverseReason.UNLIKE,
                GrowthIdemKeys.SOURCE_UNLIKE, null), authorId, RuleCode.LIKE_RECEIVED);
    }

    /** 被收藏（PRD AC14a，④ 发布）：收藏人 = 作者 → 不入账。 */
    @Async
    @TransactionalEventListener
    public void onCollected(ContentCollectedEvent e) {
        if (e.collectorId() == e.authorId()) {
            return;
        }
        accrue(new AccrualCommand(e.authorId(), RuleCode.COLLECT_RECEIVED, null,
                GrowthIdemKeys.collectReceived(e.collectorId(), e.postId()), REF_CONTENT_POST,
                String.valueOf(e.postId()), postPetIds(e.postId()), clock.instant()));
    }

    /** 取消收藏（PRD AC14a）：按原键扣回 {@code COLLECT_RECEIVED}。 */
    @Async
    @TransactionalEventListener
    public void onUncollected(ContentUncollectedEvent e) {
        if (e.collectorId() == e.authorId()) {
            return;
        }
        reverse(new ReverseCommand(GrowthIdemKeys.collectReceived(e.collectorId(), e.postId()),
                ReverseReason.UNCOLLECT, GrowthIdemKeys.SOURCE_UNCOLLECT, null), e.authorId(),
                RuleCode.COLLECT_RECEIVED);
    }

    /** 打卡：一次打卡（可含多只宠物）入账一次。 */
    @Async
    @TransactionalEventListener
    public void onCheckedIn(PlaceCheckedInEvent e) {
        accrue(new AccrualCommand(e.userId(), RuleCode.PLACE_CHECKIN, null, GrowthIdemKeys.checkin(e.checkinId()),
                REF_PLACE_CHECKIN, String.valueOf(e.checkinId()), e.petIds(), clock.instant()));
    }

    /** 里程碑（PRD AC15a）：按级别选规则；同账号同 code 第 6 次起不入账（在 {@link GrowthLedgerService} 判）。 */
    @Async
    @TransactionalEventListener
    public void onMilestone(MilestoneCompletedEvent e) {
        RuleCode rule = switch (e.level()) {
            case S -> RuleCode.MILESTONE_S;
            case M -> RuleCode.MILESTONE_M;
            case L -> RuleCode.MILESTONE_L;
        };
        accrue(new AccrualCommand(e.ownerId(), rule, null, GrowthIdemKeys.milestone(e.ownerId(), e.code(), e.petId()),
                REF_MILESTONE, e.code(), petIds(e.petId()), clock.instant()));
    }

    /** 违规下架（PRD AC7）：只扣该帖的发帖分；被赞 / 被评 / 被收藏所得不扣。 */
    @Async
    @TransactionalEventListener
    public void onRemoved(ContentRemovedEvent e) {
        reverse(new ReverseCommand(GrowthIdemKeys.post(e.postId()), ReverseReason.TAKEDOWN,
                GrowthIdemKeys.SOURCE_TAKEDOWN, null), e.authorId(), RuleCode.POST_PUBLISHED);
    }

    private void accrue(AccrualCommand cmd) {
        try {
            ledger.accrue(cmd);
        } catch (RuntimeException ex) {
            log.error("growth accrue failed: userId={} rule={} error={}", cmd.userId(), cmd.rule(),
                    ex.getClass().getSimpleName());
        }
    }

    private void reverse(ReverseCommand cmd, long userId, RuleCode rule) {
        try {
            ledger.reverse(cmd);
        } catch (RuntimeException ex) {
            log.error("growth reverse failed: userId={} rule={} error={}", userId, rule,
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * 互动类关联宠物：读多宠关联 {@code content_pets}（经 {@link ContentPetQuery}，宠物创建顺序；多宠物线合入后
     * 由 {@code content_posts.pet_id} 单值改来，CROSS-STORY 2026-10-18「其他口径」）。无关联 / 查不到不影响入账。
     */
    private List<Long> postPetIds(long postId) {
        try {
            return petIds(contentPets.petsOf(postId));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<Long> petIds(Long petId) {
        return petId == null ? null : List.of(petId);
    }

    /** 空表与 null 同义（不关联宠物 → pet_ids 列为 NULL，与单值口径一致）。 */
    private static List<Long> petIds(List<Long> petIds) {
        return petIds == null || petIds.isEmpty() ? null : List.copyOf(petIds);
    }

    private Instant orNow(Instant at) {
        return at != null ? at : clock.instant();
    }
}
