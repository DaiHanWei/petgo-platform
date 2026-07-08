package com.tailtopia.avatarmoderation.service;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.avatarmoderation.domain.AvatarDecision;
import com.tailtopia.avatarmoderation.domain.AvatarDefaults;
import com.tailtopia.avatarmoderation.domain.AvatarReview;
import com.tailtopia.avatarmoderation.domain.AvatarReviewStatus;
import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;
import com.tailtopia.avatarmoderation.event.AvatarResetEvent;
import com.tailtopia.avatarmoderation.repository.AvatarReviewRepository;
import com.tailtopia.avatarmoderation.service.AvatarReviewRouter.RoutingDecision;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 头像审核状态机服务（内容审核 story 5，§5）。用户/宠物头像「先放行、后异步审核」：先建 {@code QUEUED} 记录 →
 * 异步调 story 1 图像审核（{@link ContentModerationService#evaluate}，输入头像 URL）按 {@link AvatarReviewRouter}
 * 分档路由 → 运营在人工队列判 {@link AvatarDecision#VIOLATION} 时重置为平台默认头像并推送。
 * 与名称侧 {@code NameModerationService} <b>并列同构</b>（不强抽公共类，避免动 cm-4）。
 *
 * <p><b>编排护栏（cm-4 已踩坑并修复，本 story 严格照搬）</b>：三段编排由 {@link AvatarReviewListener} <b>跨 bean</b>
 * 调用（openReview → scoreAndRoute 返回决策 → applyScoreOutcome 落库），<b>绝不在本 service 内自调用</b>
 * {@code REQUIRES_NEW} 方法——自调用绕过 Spring 代理 → 更新不 flush，记录卡在 {@code QUEUED}。
 *
 * <p>陈旧作废用 {@code avatar_url} 版本键（D-CM3）：出结果/处置时与当前对象头像比对，不等即静默丢弃。
 * fail-closed 降级重试 ≥3 次仍失败才入队（绝不自动放行，D-CM5）。重置写默认常量（§4.2，无布尔列）。
 * 日志禁记头像 URL/签名 URL（§5.6，risk/verdict 可记）。异步只 {@code @Async} + DB 状态机，禁 MQ/缓存。
 */
@Service
public class AvatarModerationService {

    private static final Logger log = LoggerFactory.getLogger(AvatarModerationService.class);

    /** 降级重试上限（初次 + 重试；spec §5.2「重试 ≥3 次」）。 */
    static final int MAX_ATTEMPTS = 4;
    /** 指数退避基数（毫秒）：base * 2^attempt。 */
    static final long BACKOFF_BASE_MILLIS = 100L;

    private final AvatarReviewRepository reviews;
    private final ContentModerationService moderation;
    private final UserRepository users;
    private final PetProfileRepository petProfiles;
    private final ApplicationEventPublisher events;

    public AvatarModerationService(AvatarReviewRepository reviews, ContentModerationService moderation,
            UserRepository users, PetProfileRepository petProfiles, ApplicationEventPublisher events) {
        this.reviews = reviews;
        this.moderation = moderation;
        this.users = users;
        this.petProfiles = petProfiles;
        this.events = events;
    }

    // ---------- 送审开局：建记录 + 陈旧作废旧记录 ----------

    /**
     * 开启一次头像审核（§5.1）：把该对象全部非终态旧记录置 {@code STALE_DISCARDED}（陈旧作废，若在队列一并移出），
     * 新建 {@code QUEUED} 记录。返回新记录 id。
     *
     * <p>{@code REQUIRES_NEW}：由 {@code @Async} 监听器在无环境事务的新线程调用，独立事务确保 INSERT 落库
     * （[notify AFTER_COMMIT 事务吞写] 教训的防御式实践）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long openReview(AvatarSubjectType subjectType, long subjectId, String avatarUrl) {
        List<AvatarReview> active = reviews.findBySubjectTypeAndSubjectIdAndStatusIn(
                subjectType, subjectId, List.of(AvatarReviewStatus.QUEUED, AvatarReviewStatus.MANUAL_PENDING));
        for (AvatarReview old : active) {
            old.discardAsStale(); // 旧的在途/待判一律陈旧作废（移出队列 + 其在途结果回来按状态丢弃）
        }
        AvatarReview created = reviews.save(AvatarReview.queued(subjectType, subjectId, avatarUrl));
        return created.getId();
    }

    // ---------- 异步评分 + 路由 ----------

    /** {@code scoreAndRoute} 的评分结果。由监听器跨 bean 调 {@link #applyScoreOutcome} 落库——若在本类内自调用会绕过事务代理（REQUIRES_NEW 失效 → 更新不 flush，记录卡 QUEUED）。 */
    public record ScoredRouting(RoutingDecision decision, int retries) {}

    /**
     * 评分并路由（§5.2）。调 story 1 图像审核 {@code evaluate("", List.of(avatarUrl))}（降级则指数退避重试 ≥3 次），
     * 返回路由决策 + 重试次数。不参与外层事务（含三方网络调用）、<b>不落库</b>——落库由监听器跨 bean 调
     * {@link #applyScoreOutcome}（独立事务 REQUIRES_NEW），避免自调用绕过代理。
     */
    public ScoredRouting scoreAndRoute(long reviewId, String avatarUrl) {
        ModerationOutcome outcome = null;
        int attempt = 0;
        for (; attempt < MAX_ATTEMPTS; attempt++) {
            // 纯图审核：文本恒空，只送头像 URL（图像高置信违规 → IMAGE_BLOCKED；正常 → PASS；三方故障 → DEGRADED）。
            outcome = moderation.evaluate("", List.of(avatarUrl));
            if (!outcome.degraded()) {
                break; // 拿到确定评分（IMAGE_BLOCKED/PASS）即止
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                backoff(attempt);
            }
        }
        int retries = Math.min(attempt, MAX_ATTEMPTS - 1); // 重试次数（不含首次）
        if (outcome.degraded()) {
            // fail-closed 告警（护栏：仅 reviewId + reason，绝不记头像 URL）。
            log.warn("avatar moderation fail-closed → manual queue: reviewId={} degradeReason={} retries={}",
                    reviewId, outcome.degradeReason(), retries);
        }
        return new ScoredRouting(AvatarReviewRouter.route(outcome), retries);
    }

    /**
     * 评分结果落库（§5.2 陈旧作废）。落库前双重校验：①记录仍为 {@code QUEUED}（未被更高版本取代）；
     * ②{@code avatar_url} 版本键仍等于当前对象头像（用户/宠物未改新值）。任一失配 → 静默丢弃
     * （{@code STALE_DISCARDED}，不处置、不通知，D-CM3）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyScoreOutcome(long reviewId, RoutingDecision decision, int retryCount) {
        AvatarReview review = reviews.findById(reviewId).orElse(null);
        if (review == null || review.getStatus() != AvatarReviewStatus.QUEUED) {
            log.debug("avatar moderation score dropped (superseded): reviewId={}", reviewId);
            return; // 被更高版本取代 → 静默丢弃
        }
        String current = currentAvatarUrl(review.getSubjectType(), review.getSubjectId());
        if (!Objects.equals(current, review.getAvatarUrl())) {
            // 版本键失配：出结果时头像已被改新值 → 陈旧作废（不处置不通知）。
            review.discardAsStale();
            log.debug("avatar moderation score stale-discarded (avatar changed): reviewId={}", reviewId);
            return;
        }
        review.applyScore(decision.status(), decision.priority(), decision.riskScore(), decision.verdict());
    }

    // ---------- 运营处置（供 story 8 后台复用） ----------

    /**
     * 运营处置一条人工队列项（§5.3）。仅 {@code MANUAL_PENDING} 可处置（幂等 + 陈旧防御）；
     * {@code PASS} → {@code RESOLVED/PASS}（头像保留、不推送）；{@code VIOLATION} → 重置默认头像 + 推送。
     * 供 story 8 后台调用（后台 UI/审计归 story 8）。
     */
    @Transactional
    public void decide(long reviewId, AvatarDecision decision) {
        AvatarReview review = reviews.findById(reviewId)
                .orElseThrow(() -> AppException.notFound("审核记录不存在"));
        if (review.getStatus() != AvatarReviewStatus.MANUAL_PENDING) {
            throw AppException.validation("该审核记录已处置或已失效");
        }
        if (decision == AvatarDecision.PASS) {
            review.resolvePass();
            return;
        }
        // VIOLATION：处置前再校验版本键（此刻头像已被用户改新值 → 放弃处置、移出队列，D-CM3）。
        String current = currentAvatarUrl(review.getSubjectType(), review.getSubjectId());
        if (!Objects.equals(current, review.getAvatarUrl())) {
            review.discardAsStale();
            log.debug("avatar violation abandoned (avatar changed before disposal): reviewId={}", reviewId);
            return;
        }
        AvatarResetEvent event = resetToDefault(review.getSubjectType(), review.getSubjectId());
        review.resolveViolation();
        if (event != null) {
            // 负向结果 → 推送（D-CM6）；供 notify AVATAR_RESET。PASS/STALE/自动过不发事件故不推送。
            events.publishEvent(event);
        }
    }

    // ---------- 违规重置：写平台默认头像常量（§4.2，B12 不自审） ----------

    /**
     * 违规重置：把对象 {@code avatar_url} 写成平台默认常量——对所有人（含本人）即展示默认头像。
     * <b>直写实体（不经 MeService/ProfileService），故不触发送审</b>（防自审循环 B12）。返回推送事件（对象不存在则 null）。
     */
    private AvatarResetEvent resetToDefault(AvatarSubjectType type, long subjectId) {
        if (type == AvatarSubjectType.USER_AVATAR) {
            Optional<User> found = users.findById(subjectId);
            if (found.isEmpty()) {
                log.debug("avatar reset skipped, user gone");
                return null;
            }
            User user = found.get();
            user.setAvatarUrl(AvatarDefaults.DEFAULT_USER_AVATAR_URL);
            users.save(user);
            // recipient = 用户本人（== subjectId），targetRef="USER_AVATAR" 跳我的页换头像入口。
            return new AvatarResetEvent(AvatarSubjectType.USER_AVATAR, subjectId, "USER_AVATAR");
        }
        Optional<PetProfile> found = petProfiles.findById(subjectId);
        if (found.isEmpty()) {
            log.debug("avatar reset skipped, pet gone");
            return null;
        }
        PetProfile pet = found.get();
        pet.setAvatarUrl(AvatarDefaults.DEFAULT_PET_AVATAR_URL);
        petProfiles.save(pet);
        // recipient = owner；targetRef = cardToken（不可枚举），客户端跳该宠物档案编辑页换头像。
        return new AvatarResetEvent(AvatarSubjectType.PET_AVATAR, pet.getOwnerId(), pet.getCardToken());
    }

    private String currentAvatarUrl(AvatarSubjectType type, long subjectId) {
        if (type == AvatarSubjectType.USER_AVATAR) {
            return users.findById(subjectId).map(User::getAvatarUrl).orElse(null);
        }
        return petProfiles.findById(subjectId).map(PetProfile::getAvatarUrl).orElse(null);
    }

    // ---------- 队列查询（story 8 后台复用） ----------

    @Transactional(readOnly = true)
    public List<AvatarReview> pendingQueue() {
        return reviews.findByStatusOrderByCreatedAtAsc(AvatarReviewStatus.MANUAL_PENDING);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MILLIS * (1L << attempt)); // 100 / 200 / 400 ...
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
