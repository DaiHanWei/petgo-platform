package com.tailtopia.notify.service;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationAggregateDao;
import com.tailtopia.notify.repository.NotificationRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 统一推送出口（Story 6.1）。所有业务推送（6.2/6.3 经领域事件订阅）汇聚于此：
 * ① 写一行 {@code notifications}（供 6.6 通知中心） ② Redis 未读角标 {@code notify:unread:{userId}} 自增
 * ③ 经 {@code shared/im} 离线通道异步下发（携 {@code type + deepLinkToken}）。
 *
 * <p>护栏：深链用不可枚举 token（绝不顺序 id）；不引入 MQ/独立 TPNS；日志不落 PII/健康/token。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Redis 未读角标计数键前缀（仅角标用途，不当通用缓存）。 */
    public static final String UNREAD_KEY_PREFIX = "notify:unread:";

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /** 点赞推送去抖键前缀（V1.3.4 follow · AD-10）：{@code notify:likepush:{postId}}，10 分钟内同帖只推一次。 */
    public static final String LIKE_PUSH_KEY_PREFIX = "notify:likepush:";

    /** 场所评论推送去抖键前缀（D-7，Story 3.2 使用）：{@code notify:placecommentpush:{placeId}}。 */
    public static final String PLACE_COMMENT_PUSH_KEY_PREFIX = "notify:placecommentpush:";

    /**
     * 带触发人的互动类型（模板只覆盖它们）。{@link #sendInteraction} 只接受前四种；{@code PET_GIFT_RECEIVED}
     * （V1.4.0 gift Story 1.2）只经 {@link #sendGiftReceived} 写入。本集合同时是通知列表现拼、未读角标「有效未读」口径的事实源
     * （{@link InteractionActorResolver#INTERACTION_TYPE_NAMES} 由它派生）。
     */
    static final java.util.Set<NotificationType> INTERACTION_TYPES = java.util.EnumSet.of(
            NotificationType.CONTENT_LIKED, NotificationType.CONTENT_COMMENTED,
            NotificationType.USER_FOLLOWED, NotificationType.PLACE_COMMENTED,
            NotificationType.PET_GIFT_RECEIVED);

    /** 收礼聚合键前缀（V1.4.0 gift · AD-8）：{@code GIFT:PET:{petId}}，同一宠物未读即合并。不去抖（{@link #debounceKey} 返回 null）。 */
    static final String GIFT_AGGREGATE_PREFIX = "GIFT:PET:";

    /** 推送去抖窗口（AD-10）。 */
    static final Duration PUSH_DEBOUNCE = Duration.ofMinutes(10);

    /** 推送正文里评论原文的上限（T-31），超出截断加「…」。 */
    static final int PUSH_BODY_MAX = 100;

    /** 兽医推送默认语言（V1 兽医无语言偏好字段，统一印尼语）。 */
    private static final java.util.Locale INDONESIAN = java.util.Locale.forLanguageTag("id");

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final NotificationPusher pusher;
    private final org.springframework.context.MessageSource messageSource;
    private final com.tailtopia.auth.service.AccountQueryService accountQuery;
    private final NotificationAggregateDao aggregates;
    private final NotificationActorRepository actors;
    private final InteractionCopyRenderer copy;
    private final InteractionActorResolver unreadResolver;
    private final SecureRandom random = new SecureRandom();

    public NotificationService(NotificationRepository repo, StringRedisTemplate redis,
            NotificationPusher pusher, org.springframework.context.MessageSource messageSource,
            com.tailtopia.auth.service.AccountQueryService accountQuery, NotificationAggregateDao aggregates,
            NotificationActorRepository actors, InteractionCopyRenderer copy,
            InteractionActorResolver unreadResolver) {
        this.repo = repo;
        this.redis = redis;
        this.pusher = pusher;
        this.messageSource = messageSource;
        this.accountQuery = accountQuery;
        this.aggregates = aggregates;
        this.actors = actors;
        this.copy = copy;
        this.unreadResolver = unreadResolver;
    }

    /**
     * 按 type + 收件人语言渲染 push 文案（bug 20260625-105）。键 {@code notify.<TYPE>.title/body}；
     * 缺键回退调用方传入的原文案。仅用于系统推送——站内通知中心由 App 端按 type 自行本地化。
     */
    private String pushText(NotificationType type, String suffix, java.util.Locale locale, String fallback) {
        return messageSource.getMessage("notify." + type.name() + "." + suffix, null, fallback, locale);
    }

    /**
     * 发送通知。{@code targetRef} 为内部回查目标资源标识（不外泄）；自动生成不可枚举 {@code deepLinkToken}。
     * 返回落库的通知（含 token）。
     *
     * <p><b>REQUIRES_NEW 必需</b>：内容/问诊/审核订阅者是同步的 {@code @TransactionalEventListener}
     * （默认 AFTER_COMMIT 阶段），此时触发事务已提交但同步资源仍绑定。若用默认 REQUIRED，本方法会
     * 「加入」那个已提交的事务 → {@code repo.save} 的 INSERT 永不提交而被静默丢弃（角标 Redis 自增/推送
     * 却照常执行）→ 表象为「首页角标涨了但通知中心为空」。里程碑订阅者因带 {@code @Async}（另起线程、
     * 无环境事务）侥幸落库，其余四类全丢。REQUIRES_NEW 强制挂起并新开独立事务，保证通知真正提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification send(long recipientUserId, NotificationType type, String title, String body,
            String deepLinkType, String targetRef) {
        return write(recipientUserId, type, title, body, deepLinkType, targetRef, true);
    }

    /**
     * 只写通知中心、**不发系统推送**（V1.1.6 Story 6.1 · FR-76 / AD-13）。
     *
     * <p>用于 S/M 级里程碑达成：数量比 L 级多得多，推送会变成打扰；但仍要进通知中心留痕，
     * 好让"别人的互动触发了里程碑、本人当时不在现场"这件事被告知到。
     *
     * <p>🛡 <b>刻意与 {@link #send} 共用同一段落库 + 角标逻辑</b>，只切掉推送那一步 ——
     * 未读角标自增就在那段里。另写一套"只落库"的逻辑就等于把 AC「必须让角标递增」那条护栏丢了：
     * 通知进了中心而铃铛不动，用户仍需主动去翻，本 FR 直接失效。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification sendWithoutPush(long recipientUserId, NotificationType type, String title,
            String body, String deepLinkType, String targetRef) {
        return write(recipientUserId, type, title, body, deepLinkType, targetRef, false);
    }

    private Notification write(long recipientUserId, NotificationType type, String title, String body,
            String deepLinkType, String targetRef, boolean push) {
        String token = generateToken();
        Notification saved = repo.save(Notification.of(
                recipientUserId, type, title, body, deepLinkType, token, targetRef));
        // 未读角标自增（用户侧通知中心 6.6）。bug 20260625-088：Redis 抖动/不可用**不得**回滚通知落库
        // 或阻断推送——角标是派生数据，稍后由通知中心首页 / unreadCount 回库自愈。
        bumpUnreadBadge(recipientUserId);
        if (!push) {
            return saved; // 只留痕、不打扰（Story 6.1）。角标已在上一行涨过。
        }
        // 离线推送异步投递（失败不阻塞）。push 文案按收件人语言渲染（bug 20260625-105）；
        // 站内通知中心不用这里的文本（App 端按 type 自行本地化），故落库 title/body 保持不变。
        if (skipPushForVirtual(recipientUserId)) {
            return saved;
        }
        java.util.Locale locale = accountQuery.localeOf(recipientUserId);
        pusher.pushToUser(recipientUserId,
                pushText(type, "title", locale, title), pushText(type, "body", locale, body),
                deepLinkType, token, targetRef);
        return saved;
    }

    /**
     * 互动通知（V1.3.4 follow · Story 3.1 · AD-9 / AD-10）：带触发人，点赞 / 场所评论「未读即合并」。
     *
     * <ul>
     *   <li>{@code aggregateKey == null}（评论、回复、新粉丝）：每次新建一行 + 记触发人 + 角标 +1 + 推送。</li>
     *   <li>{@code aggregateKey != null}（点赞、场所评论）：一条 upsert 原子地「有未读同键行则复用，否则新建」：
     *     <ul>
     *       <li>新建 → 角标 +1；</li>
     *       <li>复用且本人是新触发人 → 刷新时间（移到最上），<b>角标不动</b>；</li>
     *       <li>复用且本人已在触发人里（取消赞再赞，T-29）→ <b>什么都不做</b>：不刷新、不推送；</li>
     *       <li>已读后再来 → 部分唯一索引不再命中 → 新建。</li>
     *     </ul></li>
     * </ul>
     *
     * <p>落库 {@code title / body} 只放兜底串，<b>不含昵称、不含评论原文</b>（AD-3：昵称读取时现查；评论原文由列表按
     * {@code comment_id} 现查）。{@code pushBodyText} 只进推送正文、<b>不落库、不进日志</b>。
     *
     * <p>推送（<b>本事务提交之后</b>，失败只丢这次推送、不回滚通知）：虚拟号 / 官方号（D-9）不推 → 去抖（点赞 {@code notify:likepush:{postId}}、场所评论
     * {@code notify:placecommentpush:{placeId}}，{@code SET NX EX 600}，抢到才推；Redis 异常照常推，T-32）→
     * 标题按收件人语言用 {@link InteractionCopyRenderer} 渲染<b>推送时刻</b>的聚合结果，正文 = 评论原文（截 100 字）或空串；
     * 触发人全被滤掉则不推并释放去抖键。只接受 {@link #INTERACTION_TYPES}，其它类型写库前即拒。
     *
     * <p>⚠️ 拉黑 / 隐藏抑制<b>不在这里</b>，仍在各监听器（理由同 {@code ContentNotifyListener} 类注释）。
     * REQUIRES_NEW 的理由同 {@link #send}。
     *
     * @return 落库（或被合并到）的那一行；T-29 跳过时也返回那一行
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification sendInteraction(long recipientUserId, NotificationType type, long actorUserId,
            String aggregateKey, String targetRef, Long commentId, String variant, String pushBodyText) {
        // 3.1 code-review F1：非互动类型在写库之前就拒绝（否则行写了、渲染推送时才炸）。
        if (!INTERACTION_TYPES.contains(type) || type == NotificationType.PET_GIFT_RECEIVED) {
            throw new IllegalArgumentException("not an interaction type: " + type);
        }
        long id;
        String token;
        if (aggregateKey == null) {
            Notification saved = repo.save(Notification.interaction(recipientUserId, type, fallbackTitle(type, variant),
                    INTERACTION_FALLBACK_BODY, type.name(), generateToken(), targetRef, null, commentId, variant));
            aggregates.addActor(saved.getId(), actorUserId);
            bumpUnreadBadge(recipientUserId);
            id = saved.getId();
            token = saved.getDeepLinkToken();
        } else {
            AggregateWrite w = writeAggregate(recipientUserId, type, actorUserId, aggregateKey, targetRef, commentId,
                    variant);
            if (w.outcome() == ActorOutcome.SAME_ACTOR) {
                // T-29：同一人取消赞再赞、仍未读 —— 不刷新时间、不顶到最上、不再推送。
                return repo.findById(w.id()).orElseThrow();
            }
            id = w.id();
            token = w.token();
        }
        Notification saved = repo.findById(id).orElseThrow();
        // 3.1 code-review F1：推送放到本事务提交之后 —— 渲染要查库，查询异常若发生在事务内会把刚写的通知一起回滚
        // （角标已 +1、去抖键已占，通知却没了）。提交后再推，任何异常都只丢这一次推送。无事务（单测）时直接执行。
        runAfterCommit(() -> {
            try {
                pushInteraction(recipientUserId, type, variant, aggregateKey, id, token, targetRef, pushBodyText);
            } catch (RuntimeException e) {
                log.warn("互动推送失败（通知已落库）notificationId={} cause={}", id, e.getClass().getSimpleName());
            }
        });
        return saved;
    }

    /** 互动通知落库兜底正文（不含昵称 / 原文，AD-3）。 */
    private static final String INTERACTION_FALLBACK_BODY = "点击查看";

    /** 聚合写入时本次触发人的三种结局。 */
    private enum ActorOutcome {
        /** 新建了一条（角标已 +1）。 */
        CREATED,
        /** 合并进未读同键行、本人是新触发人（已刷新时间；必要时补角标）。 */
        MERGED_NEW_ACTOR,
        /** 合并进未读同键行、本人已在触发人里（什么都没做，T-29 分支）。 */
        SAME_ACTOR
    }

    private record AggregateWrite(long id, String token, ActorOutcome outcome) {
    }

    /**
     * 聚合类互动的 upsert + 记触发人 + 角标（{@link #sendInteraction} 与 {@link #sendGiftReceived} 共用这一份）：
     * 新建 → 角标 +1；新触发人 → {@code touch} 刷新时间（角标不动，原条目不可见时补 +1）；同一触发人 → 不动，交调用方决定。
     */
    private AggregateWrite writeAggregate(long recipientUserId, NotificationType type, long actorUserId,
            String aggregateKey, String targetRef, Long commentId, String variant) {
        NotificationAggregateDao.UpsertResult r = aggregates.upsertAggregate(recipientUserId, type,
                fallbackTitle(type, variant), INTERACTION_FALLBACK_BODY, type.name(), generateToken(), targetRef,
                aggregateKey, commentId, variant);
        boolean newActor = aggregates.addActor(r.id(), actorUserId);
        if (r.inserted()) {
            bumpUnreadBadge(recipientUserId);
            return new AggregateWrite(r.id(), r.deepLinkToken(), ActorOutcome.CREATED);
        }
        if (newActor) {
            aggregates.touch(r.id()); // 合并：时间刷新、角标不动
            // 3.3 code-review F3：合并前这条若「不可见」（原触发人全被过滤 → 列表隐藏、角标未计，T-36），
            // 这次合进来的人让它重新可见 → 角标要补 +1，否则铃铛比可见未读少 1。
            if (wasInvisible(recipientUserId, r.id(), actorUserId)) {
                bumpUnreadBadge(recipientUserId);
            }
            return new AggregateWrite(r.id(), r.deepLinkToken(), ActorOutcome.MERGED_NEW_ACTOR);
        }
        return new AggregateWrite(r.id(), r.deepLinkToken(), ActorOutcome.SAME_ACTOR);
    }

    /** 本事务提交之后执行；无事务同步（单测直调）时立即执行。 */
    private static void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /**
     * 收礼互动通知（V1.4.0 gift Story 1.2 · AC5 · AD-8）：{@code PET_GIFT_RECEIVED}，按宠物 {@code GIFT:PET:{petId}} 未读即合并。
     *
     * <p>与点赞走同一套 upsert / 记触发人 / 角标逻辑（{@link #writeAggregate}），<b>两处不同</b>：
     * <ol>
     *   <li>同一送礼人在未读期间再送（T-29 分支）→ <b>照样 {@code touch} 刷新时间并推送</b>（每份礼物都有人花了钱）；</li>
     *   <li>推送<b>不去抖</b>（每份都推、单笔文案）。</li>
     * </ol>
     * {@code targetRef} = 宠物 id（与访客路由 {@code /pets/:petId} 同口径）；{@code commentId / variant = null}。
     *
     * <p>推送在本事务<b>提交之后</b>（失败只丢这次推送）；虚拟号 / 官方号不推；{@code pushTitle} 为空（触发人已注销 / 封号）→ 不推、仍落库。
     * {@code pushTitle} 由调用方按收件人语言渲染，<b>不落库、不进日志</b>。拉黑 / 隐藏抑制<b>不在这里</b>（在送礼监听器）。
     * REQUIRES_NEW 的理由同 {@link #send}。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification sendGiftReceived(long recipientUserId, long actorUserId, long petId, String pushTitle) {
        String aggregateKey = GIFT_AGGREGATE_PREFIX + petId;
        String targetRef = String.valueOf(petId);
        AggregateWrite w = writeAggregate(recipientUserId, NotificationType.PET_GIFT_RECEIVED, actorUserId,
                aggregateKey, targetRef, null, null);
        if (w.outcome() == ActorOutcome.SAME_ACTOR) {
            aggregates.touch(w.id()); // 同一人再送：仍顶到最上（与点赞 T-29 相反）
        }
        Notification saved = repo.findById(w.id()).orElseThrow();
        if (pushTitle != null && !pushTitle.isBlank()) {
            long id = w.id();
            String token = w.token();
            runAfterCommit(() -> {
                try {
                    if (skipPushForVirtual(recipientUserId) || accountQuery.isOfficialAccount(recipientUserId)) {
                        return;
                    }
                    pusher.pushToUser(recipientUserId, pushTitle, "", NotificationType.PET_GIFT_RECEIVED.name(),
                            token, targetRef);
                } catch (RuntimeException e) {
                    log.warn("收礼推送失败（通知已落库）notificationId={} cause={}", id, e.getClass().getSimpleName());
                }
            });
        }
        return saved;
    }

    /** 合并前（不含本次触发人）的触发人是否全被过滤。 */
    private boolean wasInvisible(long recipientUserId, long notificationId, long newActorId) {
        List<Long> before = actors.findActorIdsNewestFirst(notificationId).stream()
                .filter(id -> id != newActorId).toList();
        return !before.isEmpty() && copy.resolve(recipientUserId, before).isEmpty();
    }

    private void pushInteraction(long recipientUserId, NotificationType type, String variant, String aggregateKey,
            long notificationId, String token, String targetRef, String pushBodyText) {
        // D-9：官方内容账号（role=ADMIN 的作者 shim）不登录 App、不接收推送，与虚拟号同样跳过（3.1 code-review F3）。
        if (skipPushForVirtual(recipientUserId) || accountQuery.isOfficialAccount(recipientUserId)) {
            return;
        }
        String slot = debounceKey(aggregateKey);
        if (!acquirePushSlot(slot)) {
            return;
        }
        List<Long> actorIds = actors.findActorIdsNewestFirst(notificationId);
        Optional<InteractionCopyRenderer.ActorSummary> summary = copy.resolve(recipientUserId, actorIds);
        if (summary.isEmpty()) {
            // 触发人全被滤掉（理论上不会：本次 actor 刚过监听器的抑制判断）→ 把刚占的去抖键还回去，
            // 不让这 10 分钟内同一对象的后续互动推不出来（3.1 code-review F4）。
            releasePushSlot(slot);
            return;
        }
        java.util.Locale locale = accountQuery.localeOf(recipientUserId);
        pusher.pushToUser(recipientUserId, copy.title(type, variant, summary.get(), locale),
                truncateBody(pushBodyText), type.name(), token, targetRef);
    }

    /**
     * 推送去抖（AD-10 / D-7）：不聚合的类型每次都推；点赞 / 场所评论 10 分钟内同一对象只推一次（抢到键才推）。
     * Redis 不可用 → 照常推送（T-32：宁多勿漏），只记异常类名。
     */
    private boolean acquirePushSlot(String key) {
        if (key == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", PUSH_DEBOUNCE));
        } catch (RuntimeException e) {
            log.warn("推送去抖键不可用（照常推送）cause={}", e.getClass().getSimpleName());
            return true;
        }
    }

    private void releasePushSlot(String key) {
        if (key == null) {
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("推送去抖键释放失败 cause={}", e.getClass().getSimpleName());
        }
    }

    static String debounceKey(String aggregateKey) {
        if (aggregateKey == null) {
            return null;
        }
        if (aggregateKey.startsWith("LIKE:POST:")) {
            return LIKE_PUSH_KEY_PREFIX + aggregateKey.substring("LIKE:POST:".length());
        }
        if (aggregateKey.startsWith("COMMENT:PLACE:")) {
            return PLACE_COMMENT_PUSH_KEY_PREFIX + aggregateKey.substring("COMMENT:PLACE:".length());
        }
        return null;
    }

    /** 推送正文：评论原文截到 100 字（按码点，不劈开 emoji）加「…」（T-31）；无则空串（UI 稿 D8）。 */
    static String truncateBody(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= PUSH_BODY_MAX) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, PUSH_BODY_MAX)) + "…";
    }

    /** 落库兜底标题（沿用改造前的中文串；不含昵称，AD-3）。通知中心由 App / Story 3.3 按 type 本地化。 */
    private static String fallbackTitle(NotificationType type, String variant) {
        return switch (type) {
            case CONTENT_LIKED -> "有人赞了你的内容";
            case CONTENT_COMMENTED -> Notification.VARIANT_REPLY.equals(variant)
                    ? "有人回复了你的评论" : "有人评论了你的内容";
            case USER_FOLLOWED -> "有人关注了你";
            case PLACE_COMMENTED -> "有人评论了你添加的地点";
            case PET_GIFT_RECEIVED -> "有人给你的宠物送了礼物";
            default -> type.name();
        };
    }

    /**
     * 推送给兽医（Story 6.2 新问诊请求）。兽医为独立角色（v_{vetId}）；V1 兽医侧无 6.6 通知中心，
     * 故仅离线推送 + 工作台深链，不写用户通知中心行 / 不增用户角标。
     */
    public void sendToVet(long vetId, NotificationType type, String title, String body, String deepLinkType) {
        // 兽医侧 V1 无语言偏好，统一印尼语渲染（bug 20260625-105）。
        pusher.pushToVet(vetId,
                pushText(type, "title", INDONESIAN, title), pushText(type, "body", INDONESIAN, body),
                deepLinkType, null, null);
    }

    /**
     * 未读角标 +1，容错。bug 20260625-088：③ Redis 异常吞掉不冒泡（否则整个 {@code send} 事务回滚→
     * 通知根本没落库、推送也发不出）；④ INCR 命中被 evict/重启清空的缺失键会从 0 起算导致少算——
     * 返回 1 时回库用真实未读数纠正（同事务已含刚落库这条），避免 evict 后角标长期偏小。
     */
    private void bumpUnreadBadge(long recipientUserId) {
        try {
            String key = UNREAD_KEY_PREFIX + recipientUserId;
            Long newCount = redis.opsForValue().increment(key);
            if (newCount != null && newCount == 1L) {
                // V1.3.4 · 3.3 code-review F4：自愈值用与通知中心同一口径的「有效未读数」（T-36：不可见条目不计）。
                long dbUnread = unreadResolver.effectiveUnreadCount(recipientUserId);
                if (dbUnread > 1) {
                    redis.opsForValue().set(key, String.valueOf(dbUnread));
                }
            }
        } catch (RuntimeException e) {
            log.warn("未读角标自增失败（不阻断通知落库/推送）recipientUserId={} cause={}",
                    recipientUserId, e.getClass().getSimpleName());
        }
    }

    /**
     * 发送「文案带参数」的通知（留存手册抓手 1）。与 {@link #send} 的唯一差别：push 文案的 i18n 键
     * 由调用方显式给出（{@code copyKey}，如 {@code LIFECYCLE_D1.RECORD}），且支持 {@code {0}} 占位符注入。
     *
     * <p><b>为什么必须有这个重载</b>：{@link #send} 用 {@code notify.<TYPE>.body} 取文案且 {@code args=null}，
     * 一个 type 只能有一句<b>静态</b>话。生日推送就栽在这上面——dispatcher 明明拼好了「Mochi 明天满 3 岁」，
     * 却被静态键 {@code notify.PET_BIRTHDAY.body=Hari ini ulang tahun anabulmu}（"今天是你家宠物生日"）
     * 整句覆盖：宠物名没了，"明天"还被说成"今天"。而留存手册的铁律恰恰是<b>文案必须带宠物名</b>
     * ——「记录 Mochi 的一个瞬间」和「回来看看」是两个转化率量级。
     *
     * <p>⚠️ {@code args != null} 时 Spring 会走 {@code MessageFormat}，此时 i18n 串里的单引号必须写成
     * {@code ''}，否则会被当成引用块吞掉后面的占位符。新增串务必自查。
     *
     * @param copyKey 文案键后缀，完整键为 {@code notify.<copyKey>.title/body}。
     * @param args    占位符实参（如宠物名）；{@code null} 表示无参（退化为静态取串）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification sendWithCopy(long recipientUserId, NotificationType type, String copyKey,
            Object[] args, String fallbackTitle, String fallbackBody, String targetRef) {
        String token = generateToken();
        // 落库 title/body 用兜底原文（通知中心由 App 端按 type + variant 自行本地化，不读这两列）。
        Notification saved = repo.save(Notification.of(
                recipientUserId, type, fallbackTitle, fallbackBody, type.name(), token, targetRef));
        bumpUnreadBadge(recipientUserId);
        if (skipPushForVirtual(recipientUserId)) {
            return saved;
        }
        java.util.Locale locale = accountQuery.localeOf(recipientUserId);
        pusher.pushToUser(recipientUserId,
                formatCopy(copyKey, "title", args, locale, fallbackTitle),
                formatCopy(copyKey, "body", args, locale, fallbackBody),
                type.name(), token, targetRef);
        return saved;
    }

    /**
     * 虚拟号（VIRTUAL）没有 IM 账号，推过去必回 90001——直接跳过推送（通知中心照常落库 + 角标）。
     * 与 {@code localeOf} 同在本方法的 REQUIRES_NEW 事务里，findById 命中一级缓存，不多打一次库。
     */
    private boolean skipPushForVirtual(long recipientUserId) {
        if (accountQuery.isVirtual(recipientUserId)) {
            log.debug("跳过离线推送：收件人为虚拟号 recipientUserId={}", recipientUserId);
            return true;
        }
        return false;
    }

    /** 按 {@code notify.<copyKey>.<suffix>} 取串并注入 {@code args}；缺键回退 {@code fallback}。 */
    private String formatCopy(String copyKey, String suffix, Object[] args, java.util.Locale locale,
            String fallback) {
        return messageSource.getMessage("notify." + copyKey + "." + suffix, args, fallback, locale);
    }

    /**
     * 运营通知批量写入通知中心（Story 6.1 / 6.2）。不发推送——推送由 OpsNotificationDispatcher 逐人同步下发。
     *
     * <p>每个收件人写一行 {@link NotificationType#OPS_ANNOUNCEMENT}：{@code deepLinkType = "OPS_ANNOUNCEMENT"}、
     * {@code targetRef} = 活动 {@code publicToken}、{@code deepLinkToken} 为本类 32 位 base62。
     * title / body 在写入时按收件人语言定死（AD-17）：{@code "en".equalsIgnoreCase(locale)} → EN，
     * 其余（{@code id}、null、空串、其它语言）→ ID（与 {@code AccountQueryService.localeOf} 同口径）。
     * 每人照常 {@link #bumpUnreadBadge}（Redis 失败只记日志不回滚），但<b>统一挪到本事务提交之后</b>
     * （{@code afterCommit}）：整批是一个事务，若中途失败回滚，提交前就 INCR 的角标会凭空多出来；
     * 无事务同步时（如单测直调）退化为立即加。<b>不</b>调 pusher、<b>不</b>查 messageSource。
     *
     * @return 已保存的行，顺序同入参
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.List<Notification> writeOpsAnnouncements(
            com.tailtopia.notify.ops.domain.OpsNotificationCampaign campaign,
            java.util.List<com.tailtopia.notify.ops.domain.OpsRecipient> recipients) {
        java.util.List<Notification> saved = new java.util.ArrayList<>(recipients.size());
        for (com.tailtopia.notify.ops.domain.OpsRecipient r : recipients) {
            boolean en = "en".equalsIgnoreCase(r.locale());
            String title = en ? campaign.getTitleEn() : campaign.getTitleId();
            String body = en ? campaign.getBodyEn() : campaign.getBodyId();
            saved.add(repo.save(Notification.of(r.userId(), NotificationType.OPS_ANNOUNCEMENT, title, body,
                    NotificationType.OPS_ANNOUNCEMENT.name(), generateToken(), campaign.getPublicToken())));
        }
        java.util.List<Long> badgeUserIds = recipients.stream()
                .map(com.tailtopia.notify.ops.domain.OpsRecipient::userId).toList();
        Runnable bumpAll = () -> badgeUserIds.forEach(this::bumpUnreadBadge);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            bumpAll.run();
                        }
                    });
        } else {
            bumpAll.run();
        }
        return saved;
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
