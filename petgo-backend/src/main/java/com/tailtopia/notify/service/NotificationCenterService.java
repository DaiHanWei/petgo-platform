package com.tailtopia.notify.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.CommentQueryService;
import com.tailtopia.content.service.CommentQueryService.NotificationCommentSnapshot;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.dto.NotificationItem;
import com.tailtopia.notify.dto.NotificationPage;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.port.NotificationGiftLines;
import com.tailtopia.notify.port.NotificationPetNames;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.notify.service.InteractionCopyRenderer.ActorSummary;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.ops.target.OpsTargetView;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知中心读取/已读/角标（Story 6.6）。读自己 6.1 建的 {@code notifications} 表与 Redis 角标键，
 * <b>不跨模块 join</b>；对外只暴露 token + deepLinkType（不返回顺序 id/target_ref）。
 *
 * <h2>V1.3.4 follow · Story 3.3：互动条目现拼「谁 + 做了什么」（AD-11）</h2>
 * 四种互动类型且有触发人行的条目，按<b>接收方账号语言</b>（T-38）用 {@link InteractionCopyRenderer}（与推送同一模板）现拼
 * {@code title / body}，并带最新触发人与人数。一页所需外部取数<b>各一次</b>（NFR3 / AD-16）：① 触发人行
 * ② 触发人投影（昵称 + token + 是否有效）③ 接收方隐藏集合 ④ 评论快照（有评论条目才查）⑤ 关注关系（有新粉丝条目才查）；
 * 本页没有候选则一个都不调。跨模块只走对方的 service / 读端口，不写跨模块 SQL。
 *
 * <p>⚠️ T-36：触发人<b>全部</b>被过滤（注销 / 封号 / 被我隐藏）的条目<b>从列表隐藏</b>，未读角标同口径不计
 * （首页校准、缺键回算、写入时的角标自愈都用 {@link InteractionActorResolver} 同一口径）。分页游标仍按原始行推进；
 * 一页全被隐藏而后面还有时最多再补取 {@link #MAX_REFILL_ROUNDS} 轮，不给 App「空页 + hasMore」。
 * 首页把未读互动条目与本页候选合成一批解析触发人，①②③ 仍各一次。
 * 合并会把 {@code created_at} 刷到当前：翻页期间被合并的那条会跳回首页，本次翻页看不到，重进即可。
 */
@Service
public class NotificationCenterService {

    private static final Logger log = LoggerFactory.getLogger(NotificationCenterService.class);

    /**
     * 一页现拼后若一条可见的都不剩、而后面还有（被隐藏的条目恰好占满一页），最多再往后补取几轮（3.3 code-review F2）——
     * 否则 App 拿到「空页 + hasMore」会画成空态、再也翻不动。补取轮次有界，防止整库都是隐藏条目时拖垮请求。
     */
    static final int MAX_REFILL_ROUNDS = 3;

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final InteractionActorResolver actors;
    private final AccountQueryService accounts;
    private final CommentQueryService comments;
    private final FollowReader followReader;
    private final InteractionCopyRenderer copy;
    private final OpsNotificationCampaignRepository campaigns;
    /** 收礼条目的宠物名（V1.4.0 gift Story 1.2 · AC5.4）；缺 bean（切片测试）时按 noPet 文案渲染。 */
    private final ObjectProvider<NotificationPetNames> petNames;
    /** 收礼单人条目「送的是什么」（V1.4.0 gift L2 第二轮）；缺 bean / 异常时按「mengirim hadiah」渲染。 */
    private final ObjectProvider<NotificationGiftLines> giftLines;

    /**
     * 圈「本条通知期间送的礼」时的容差：送达时刻（送达事务的 {@code now()}）早于送礼人并入通知的时刻
     * （提交后监听器里另起事务的 {@code now()}），差的是一个事务的时长。
     */
    static final java.time.Duration GIFT_SINCE_TOLERANCE = java.time.Duration.ofSeconds(10);

    public NotificationCenterService(NotificationRepository repo, StringRedisTemplate redis,
            InteractionActorResolver actors, AccountQueryService accounts, CommentQueryService comments,
            FollowReader followReader, InteractionCopyRenderer copy, OpsNotificationCampaignRepository campaigns,
            ObjectProvider<NotificationPetNames> petNames, ObjectProvider<NotificationGiftLines> giftLines) {
        this.petNames = petNames;
        this.giftLines = giftLines;
        this.repo = repo;
        this.redis = redis;
        this.actors = actors;
        this.accounts = accounts;
        this.comments = comments;
        this.followReader = followReader;
        this.copy = copy;
        this.campaigns = campaigns;
    }

    private static String unreadKey(long userId) {
        return NotificationService.UNREAD_KEY_PREFIX + userId;
    }

    /**
     * 倒序游标分页。
     *
     * <p>游标是 {@link KeysetCursor}（base64url 的 {@code (createdAt, id)}，首页 null）。
     * 对客户端是<b>不透明串</b>：App 只把 {@code nextCursor} 原样回传，不解析
     * （已核对 Flutter 侧 `NotificationPage`）。
     */
    @Transactional(readOnly = true)
    public NotificationPage list(long userId, String cursor, int limit) {
        KeysetCursor c = parseCursor(cursor);
        // 首页：未读互动条目与本页候选合成<b>一批</b>解析触发人 —— 角标校准不再单独多跑一遍 ①②③（3.3 code-review F6）。
        List<Long> unreadIds = cursor == null ? actors.unreadInteractionIds(userId) : List.of();
        List<NotificationItem> items = new ArrayList<>();
        Map<Long, Optional<ActorSummary>> firstSummaries = Map.of();
        boolean hasMore;
        Notification last = null;
        int round = 0;
        do {
            List<Notification> rows = repo.findPageBefore(
                    userId, c.createdAt(), c.id(), PageRequest.of(0, limit + 1));
            hasMore = rows.size() > limit;
            List<Notification> pageRows = hasMore ? rows.subList(0, limit) : rows;
            Rendered r = renderPage(userId, pageRows, round == 0 ? unreadIds : List.of());
            if (round == 0) {
                firstSummaries = r.summaries();
            }
            items.addAll(r.items());
            if (!pageRows.isEmpty()) {
                last = pageRows.get(pageRows.size() - 1);
                c = new KeysetCursor(last.getCreatedAt(), last.getId());
            }
            round++;
        } while (items.isEmpty() && hasMore && round <= MAX_REFILL_ROUNDS);
        String nextCursor = hasMore && last != null
                ? new KeysetCursor(last.getCreatedAt(), last.getId()).encode()
                : null;
        // 打开通知中心（首页）时以 DB 真实未读数校准 Redis 角标，自愈计数漂移
        // （如计数器残留致角标>0 但列表空，或行被清而计数未减）。仅校准计数，不改已读态。
        // ⚠️ T-36：触发人全被过滤（列表里看不见）的未读互动条目不计。
        if (cursor == null) {
            long actualUnread = Math.max(0, repo.countByRecipientUserIdAndReadIsFalse(userId)
                    - InteractionActorResolver.invisibleCount(firstSummaries, unreadIds));
            // 🔎 漂移可观测（2026-08-19 加）：校准前后不一致说明角标确实漂了。
            // 线上反馈过「铃铛有数字、点进去没有新消息」，但代码里已知的四条成因
            // （INSERT 被静默丢弃 / 角标不自愈 / 前端刷太早 / 失败画成空态）都已修，
            // 复现不出来。此处如实记下每一次真实漂移，把「查不出」变成「下次有据可查」。
            // 只记内部 userId 与两个计数，不含任何 PII / 通知内容。
            String beforeRaw = redis.opsForValue().get(unreadKey(userId));
            if (beforeRaw != null && !beforeRaw.equals(String.valueOf(actualUnread))) {
                log.warn("未读角标与库不一致，已按库校准 userId={} badgeWas={} actualUnread={} listSize={}",
                        userId, beforeRaw, actualUnread, items.size());
            }
            redis.opsForValue().set(unreadKey(userId), String.valueOf(actualUnread));
        }
        return new NotificationPage(items, nextCursor, hasMore);
    }

    /**
     * 运营通知（{@code OPS_ANNOUNCEMENT}）按 targetRef（活动 publicToken）<b>一页一次</b>批量回查活动 → 跳转目标。
     * 本页没有运营通知时不查库。
     */
    private Map<String, OpsTargetView> opsTargetsOf(List<Notification> pageRows) {
        Set<String> opsTokens = pageRows.stream()
                .filter(n -> n.getType() == NotificationType.OPS_ANNOUNCEMENT && n.getTargetRef() != null)
                .map(Notification::getTargetRef)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return opsTokens.isEmpty()
                ? Map.of()
                : campaigns.findByPublicTokenIn(opsTokens).stream().collect(Collectors.toMap(
                        OpsNotificationCampaign::getPublicToken,
                        c -> OpsTargetView.of(c.getTargetType(), c.getTargetValue()),
                        (a, b) -> a));
    }

    /**
     * 原样返回的条目（非互动类型 / 存量）。运营通知附带 {@code opsTarget}；targetRef 为 null 或查不到活动
     * （理论上不会发生，活动不提供删除）给 {@code NONE}。其它类型 opsTarget = null。
     */
    private static NotificationItem plain(Notification n, Map<String, OpsTargetView> opsTargets) {
        if (n.getType() != NotificationType.OPS_ANNOUNCEMENT) {
            return NotificationItem.from(n);
        }
        OpsTargetView none = OpsTargetView.of(OpsTargetType.NONE, null);
        // Map.of() 不接受 null 键：targetRef 为 null 先判，直接给 NONE
        return NotificationItem.from(n, n.getTargetRef() == null ? none : opsTargets.getOrDefault(n.getTargetRef(), none));
    }

    /** 未读角标：读 Redis；缺值按库回算并回填（容错，不依赖角标键永不丢）。 */
    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        String v = redis.opsForValue().get(unreadKey(userId));
        if (v != null) {
            try {
                return Math.max(0, Long.parseLong(v));
            } catch (NumberFormatException ignored) {
                // 落到回算
            }
        }
        long recomputed = actors.effectiveUnreadCount(userId);
        redis.opsForValue().set(unreadKey(userId), String.valueOf(recomputed));
        return recomputed;
    }

    // ---------- Story 3.3：互动条目现拼 ----------

    /** 一页的渲染结果：可见条目 + 本批触发人摘要（首页角标校准复用）。 */
    private record Rendered(List<NotificationItem> items, Map<Long, Optional<ActorSummary>> summaries) {
    }

    /**
     * 一页条目：候选（四种互动类型）批量现拼；其余原样（运营通知附带 opsTarget）；触发人全被过滤的条目隐藏（T-36）。
     *
     * @param extraIds 与本页候选<b>同一批</b>解析的其它通知 id（首页角标校准用的未读互动条目）
     */
    private Rendered renderPage(long userId, List<Notification> pageRows, List<Long> extraIds) {
        Set<Long> batch = new LinkedHashSet<>();
        for (Notification n : pageRows) {
            if (NotificationService.INTERACTION_TYPES.contains(n.getType())) {
                batch.add(n.getId());
            }
        }
        batch.addAll(extraIds);
        Map<String, OpsTargetView> opsTargets = opsTargetsOf(pageRows);
        if (batch.isEmpty()) {
            return new Rendered(pageRows.stream().map(n -> plain(n, opsTargets)).toList(), Map.of());
        }
        // ① ② ③ 各一次
        Map<Long, Optional<ActorSummary>> summaries = actors.summarize(userId, batch);
        // ④ 评论快照：只对有可显示触发人的评论条目；⑤ 新粉丝关系：只对有可显示触发人的新粉丝条目
        Set<Long> commentIds = new LinkedHashSet<>();
        Set<Long> followerIds = new LinkedHashSet<>();
        Set<Long> giftPetIds = new LinkedHashSet<>();
        Map<Long, Long> giftSingleSender = new java.util.HashMap<>();
        boolean anyVisible = false;
        for (Notification n : pageRows) {
            Optional<ActorSummary> s = summaries.get(n.getId());
            if (s == null || s.isEmpty()) {
                continue;
            }
            anyVisible = true;
            if (n.getType() == NotificationType.CONTENT_COMMENTED && n.getCommentId() != null) {
                commentIds.add(n.getCommentId());
            } else if (n.getType() == NotificationType.USER_FOLLOWED) {
                followerIds.add(s.get().latestActorId());
            } else if (n.getType() == NotificationType.PET_GIFT_RECEIVED) {
                Long petId = giftPetId(n);
                if (petId != null) {
                    giftPetIds.add(petId);
                    if (s.get().others() == 0) {
                        giftSingleSender.put(n.getId(), s.get().latestActorId());
                    }
                }
            }
        }
        Map<Long, NotificationCommentSnapshot> snapshots = commentIds.isEmpty() ? Map.of()
                : comments.notificationSnapshots(commentIds);
        Map<Long, FollowState> states = followerIds.isEmpty() ? Map.of()
                : followReader.followStates(userId, followerIds);
        // ⑥ 收礼条目的宠物名：本页有收礼条目才查，一次批量（V1.4.0 gift Story 1.2 · AC5.4）
        Map<Long, String> names = giftPetIds.isEmpty() ? Map.of() : petNamesOf(giftPetIds);
        Locale locale = anyVisible ? accounts.localeOf(userId) : null;
        // ⑦ 收礼单人条目送的是什么（L2 第二轮）：本页有才查，触发人并入时刻一次 + 订单一次
        Map<Long, String> giftLabels = giftSingleSender.isEmpty() ? Map.of()
                : giftLabelsOf(pageRows, giftSingleSender, locale);

        List<NotificationItem> items = new ArrayList<>(pageRows.size());
        for (Notification n : pageRows) {
            Optional<ActorSummary> s = NotificationService.INTERACTION_TYPES.contains(n.getType())
                    ? summaries.get(n.getId()) : null;
            if (s == null) {
                items.add(plain(n, opsTargets)); // 非互动类型（含运营通知），或上线前的存量（无触发人行）
            } else if (s.isPresent()) {
                items.add(render(n, s.get(), locale, snapshots, states, names, giftLabels));
            }
            // s.isEmpty()：触发人全被过滤 → 从列表隐藏（T-36）
        }
        return new Rendered(items, summaries);
    }

    /** 收礼条目的 targetRef = 宠物 id（{@code NotificationService.sendGiftReceived}）；坏值按查不到处理。 */
    private static Long giftPetId(Notification n) {
        if (n.getTargetRef() == null) {
            return null;
        }
        try {
            return Long.valueOf(n.getTargetRef());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 收礼单人条目的礼物片段（「Tulang ×5」）。端口缺失或异常 → 空（按「mengirim hadiah」渲染，不拖垮通知中心）。
     *
     * @param senderByNotification 通知 id → 唯一可显示的送礼人
     */
    private Map<Long, String> giftLabelsOf(List<Notification> pageRows, Map<Long, Long> senderByNotification,
            Locale locale) {
        NotificationGiftLines port = giftLines.getIfAvailable();
        if (port == null) {
            return Map.of();
        }
        try {
            Map<Long, Instant> since = actors.actedAt(senderByNotification);
            Map<Long, NotificationGiftLines.Query> queries = new java.util.HashMap<>();
            for (Notification n : pageRows) {
                Long sender = senderByNotification.get(n.getId());
                Long petId = giftPetId(n);
                Instant at = since.get(n.getId());
                if (sender != null && petId != null && at != null) {
                    queries.put(n.getId(), new NotificationGiftLines.Query(petId, sender,
                            at.minus(GIFT_SINCE_TOLERANCE)));
                }
            }
            if (queries.isEmpty()) {
                return Map.of();
            }
            Map<Long, String> out = new java.util.HashMap<>();
            port.linesOf(queries).forEach((id, line) -> out.put(id,
                    InteractionCopyRenderer.giftLabel(line.nameEn(), line.nameId(), line.quantity(), locale)));
            return out;
        } catch (RuntimeException e) {
            log.warn("通知列表取礼物失败（按通用收礼文案渲染）cause={}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** 宠物名一次批量取；端口缺失或异常 → 空（按 noPet 文案渲染，不拖垮通知中心）。 */
    private Map<Long, String> petNamesOf(Set<Long> petIds) {
        NotificationPetNames port = petNames.getIfAvailable();
        if (port == null) {
            return Map.of();
        }
        try {
            Map<Long, String> m = port.namesOf(petIds);
            return m == null ? Map.of() : m;
        } catch (RuntimeException e) {
            log.warn("通知列表取宠物名失败（按无宠物名渲染）cause={}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private NotificationItem render(Notification n, ActorSummary s, Locale locale,
            Map<Long, NotificationCommentSnapshot> snapshots, Map<Long, FollowState> states, Map<Long, String> names,
            Map<Long, String> giftLabels) {
        String title = n.getType() == NotificationType.PET_GIFT_RECEIVED
                ? copy.title(n.getType(), n.getVariant(), s, locale, petNameOf(n, names), giftLabels.get(n.getId()))
                : copy.title(n.getType(), n.getVariant(), s, locale);
        String body = null;
        Boolean iFollow = null;
        Boolean followsMe = null;
        boolean bodyDeleted = false;
        if (n.getType() == NotificationType.CONTENT_COMMENTED && n.getCommentId() != null) {
            NotificationCommentSnapshot c = snapshots.get(n.getCommentId());
            bodyDeleted = c == null || c.gone();
            body = bodyDeleted ? copy.commentDeleted(locale) : c.body();
        } else if (n.getType() == NotificationType.USER_FOLLOWED) {
            FollowState f = states.getOrDefault(s.latestActorId(), FollowState.NONE);
            iFollow = f.iFollow();
            followsMe = f.followsMe();
        }
        return NotificationItem.rendered(n, title, body, s.latestActorId(), s.latestNickname(), s.latestToken(),
                s.total(), iFollow, followsMe, bodyDeleted);
    }

    private static String petNameOf(Notification n, Map<Long, String> names) {
        Long petId = giftPetId(n);
        return petId == null ? null : names.get(petId);
    }

    /** 标记单条已读（token 定位，仅本人，否则 404 防枚举）+ 角标递减（不低于 0）。 */
    @Transactional
    public void markRead(long userId, String token) {
        Notification n = repo.findByDeepLinkTokenAndRecipientUserId(token, userId)
                .orElseThrow(() -> AppException.notFound("通知不存在"));
        if (!n.isRead()) {
            n.markRead();
            repo.save(n);
            decrementBadge(userId);
        }
    }

    /** 全部标记已读 + 角标清零。 */
    @Transactional
    public void markAllRead(long userId) {
        List<Notification> unread = repo.findByRecipientUserIdAndReadIsFalse(userId);
        for (Notification n : unread) {
            n.markRead();
        }
        repo.saveAll(unread);
        redis.opsForValue().set(unreadKey(userId), "0");
    }

    private void decrementBadge(long userId) {
        Long after = redis.opsForValue().decrement(unreadKey(userId));
        if (after != null && after < 0) {
            redis.opsForValue().set(unreadKey(userId), "0");
        }
    }

    // ---------- 游标 ----------

    /**
     * 解析游标。
     *
     * <p>🔴 坏游标退化成首页而不是报错：游标是客户端传回来的，
     * 让整个通知中心 4xx/5xx 等于把用户锁在门外。
     */
    private static KeysetCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return KeysetCursor.firstPage();
        }
        KeysetCursor parsed = KeysetCursor.decodeOrNull(cursor);
        if (parsed != null) {
            return parsed;
        }
        // 过渡兼容：老客户端手上还捏着旧格式（纯 epochMillis）的游标。
        // 用 Long.MIN_VALUE 让「同刻」分支恒不命中 → 行为与老实现逐字一致（仍会漏，
        // 但不会因为换了格式而报错或错位）。老客户端翻完这一轮就没有旧游标了。
        try {
            return new KeysetCursor(Instant.ofEpochMilli(Long.parseLong(cursor.trim())),
                    Long.MIN_VALUE);
        } catch (NumberFormatException e) {
            return KeysetCursor.firstPage();
        }
    }
}
